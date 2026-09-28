# GreenNotify

Sistema de notificações próprio: um servidor Node.js (HTTP puro, **sem HTTPS**) que recebe
pedidos de notificação via `GET` (nada de webhooks) e um app Android que mantém uma conexão
persistente (WebSocket) para exibir as notificações no celular, com o motivo incluído.

## 1. Servidor (`server/`)

### Rodar

```bash
cd server
npm install
ADMIN_KEY=escolha-uma-chave-forte PORT=8080 node index.js
```

Por padrão sobe em `http://0.0.0.0:8080`. Deixe a máquina acessível na sua rede local
(ou VPN) — como é HTTP puro, **não exponha isso diretamente na internet** sem pelo menos
um firewall/VPN, já que as chaves trafegam em texto claro.

### Cadastrar um dispositivo (o celular)

```
GET /register?adminKey=SUA_ADMIN_KEY&deviceId=meu-celular&name=Pixel+8
```

Retorna `apiKey` — copie esse valor para dentro do app Android.

### Enviar uma notificação (usado pelas suas outras aplicações)

```
GET /notify?key=API_KEY&deviceId=meu-celular&title=Servidor+caiu&message=CPU+100%25&reason=Alerta+do+monitoramento&app=Zabbix
```

- `key` — apiKey do dispositivo (obrigatório)
- `deviceId` — obrigatório
- `title` — título da notificação
- `message` — mensagem
- `reason` — o motivo (mostrado destacado na notificação do celular)
- `app` — nome da aplicação que está enviando (aparece como "Origem")

Se o celular estiver conectado (app aberto/serviço rodando), a notificação chega na hora via
WebSocket. Se estiver offline, fica guardada e é entregue assim que ele reconectar.

### Outros endpoints (todos GET)

- `GET /list?key=&deviceId=` — lista notificações do dispositivo
- `GET /delete?key=&deviceId=&id=` — remove uma notificação (`id=all` remove todas)
- `GET /ack?key=&deviceId=&id=` — marca como entregue/lida
- `GET /health` — healthcheck

Os dados ficam persistidos em `server/data.json` (criado automaticamente).

## 2. App Android (`app/`)

Projeto Android Studio (Kotlin) já dentro deste repositório. Abra a pasta raiz
`GreenNotify` no Android Studio e deixe o Gradle sincronizar (é necessário Android
Studio/JDK — não foi possível compilar neste ambiente sem JDK instalado).

### Uso

1. Abra o app, preencha:
   - **URL do servidor**: ex. `http://192.168.0.10:8080` (IP da máquina onde o servidor roda)
   - **ID do dispositivo**: o mesmo `deviceId` usado no `/register`
   - **Chave de API**: a `apiKey` retornada pelo `/register`
2. Toque em **Salvar configuração**.
3. Toque em **Iniciar conexão** — isso sobe um serviço em primeiro plano (notificação
   discreta e permanente) que mantém o WebSocket conectado ao servidor, com reconexão
   automática caso a conexão caia.
4. Pronto: qualquer `GET /notify` feito para esse `deviceId` aparece como notificação
   nativa no celular, mostrando título, mensagem, motivo e a aplicação de origem.

O app pede a permissão de notificações (Android 13+) na primeira abertura.

## 3. Integrando suas outras aplicações

Qualquer aplicação (script, backend, IoT, cron job) só precisa fazer uma requisição
`GET` simples, sem precisar expor um endpoint de webhook:

```bash
curl "http://SEU_SERVIDOR:8080/notify?key=API_KEY&deviceId=meu-celular&title=Backup+concluido&message=Backup+diario+ok&reason=Rotina+agendada&app=BackupScript"
```

Funciona de qualquer linguagem (Python `requests.get(...)`, PHP `file_get_contents`, etc.)
sem necessidade de bibliotecas especiais, HTTPS ou servidor próprio para receber callbacks.

## Segurança (importante)

- Comunicação é **HTTP puro** (a pedido) — as chaves de API trafegam em texto claro. Use
  isso apenas em rede local/VPN confiável, nunca exposto diretamente à internet pública.
- Cada dispositivo tem sua própria `apiKey`, e cadastro de novos dispositivos exige a
  `ADMIN_KEY` do servidor — troque o valor padrão via variável de ambiente.
- Notificações ficam persistidas até serem confirmadas (`ack`) ou apagadas (`delete`).
