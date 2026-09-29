# GreenNotify

Sistema de notificações próprio: um servidor Node.js (HTTP puro, **sem HTTPS**) que recebe
pedidos de notificação via `POST` (nada de webhooks — a aplicação que quer notificar você é
quem chama o servidor) e um app Android que mantém uma conexão persistente (WebSocket) para
exibir as notificações no celular, com motivo, link e um histórico onde dá pra marcar como
concluída ou arquivar.

Documentação completa de cada parte:

- [`server/README.md`](server/README.md) — como rodar, subir no Pterodactyl e todos os
  endpoints com exemplos em curl/Python/PHP/PowerShell
- [`AI_INTEGRATION.md`](AI_INTEGRATION.md) — cola isso no contexto de uma IA (Claude,
  Copilot, etc.) e só peça "integra as notificações do GreenNotify aqui" — tem tudo que
  ela precisa saber pra gerar o código sozinha
- Este arquivo — visão geral rápida

## 1. Servidor (`server/`)

```bash
cd server
npm install
ADMIN_KEY=escolha-uma-chave-forte-e-longa PORT=8080 node index.js
```

A `ADMIN_KEY` é obrigatória e precisa ter pelo menos 16 caracteres — sem ela o servidor
não sobe. Veja [`server/.env.example`](server/.env.example).

Por padrão sobe em `http://0.0.0.0:8080`. Os dados ficam num banco SQLite local
(`server/data.sqlite`, via `better-sqlite3`), não precisa instalar nada além do Node.

Deixe a máquina acessível na sua rede local (ou VPN) — como é HTTP puro, **não exponha
isso diretamente na internet** sem pelo menos um firewall/VPN, já que as chaves trafegam
em texto claro.

### Cadastrar um dispositivo (o celular)

```bash
curl -X POST "http://SEU_SERVIDOR:8080/register" \
  -H "Content-Type: application/json" \
  -d '{"adminKey":"SUA_ADMIN_KEY","deviceId":"meu-celular","name":"Pixel 8"}'
```

Retorna `apiKey` — copie esse valor para dentro do app Android.

### Enviar uma notificação (usado pelas suas outras aplicações)

```bash
curl -X POST "http://SEU_SERVIDOR:8080/notify" \
  -H "Content-Type: application/json" \
  -d '{
    "key": "API_KEY",
    "deviceId": "meu-celular",
    "title": "Servidor caiu",
    "message": "CPU em 100%",
    "reason": "Alerta do monitoramento",
    "app": "Zabbix",
    "link": "https://painel.exemplo.com/incidentes/42"
  }'
```

- `key` — apiKey do dispositivo (obrigatório)
- `deviceId` — obrigatório
- `title` / `message` / `reason` — título, mensagem e o motivo (destacado na notificação)
- `app` — nome de quem está enviando (aparece como "Origem")
- `topic` — assunto opcional: notificações com o mesmo `topic` ficam empilhadas juntas no
  celular (ex: `"Ticket #123"`). Sem ele, o app agrupa pelo `app`.
- `link` — link opcional (ticket, canal do Discord, pedido, etc). Ao tocar na notificação
  ou no item da lista, o link abre direto no celular.

Se o celular estiver conectado (app aberto/serviço rodando), a notificação chega na hora via
WebSocket. Se estiver offline, fica guardada e é entregue assim que ele reconectar.

### Outros endpoints (todos POST, corpo em JSON)

- `/list` — lista notificações, filtrando por `status` (`pending`, `done`, `archived`).
  Paginado, mais recentes primeiro: `limit` (padrão 100, máximo 500) e `offset`
  (padrão 0). Sem `limit`, vem no máximo 100 — pra ver mais, aumente o `offset`.
- `/complete` — marca uma notificação como concluída
- `/move` — move para outro status (ex: arquivar)
- `/delete` — remove uma notificação (`id: "all"` remove todas)
- `/ack` — marca como entregue/lida
- `GET /health` — healthcheck (esse único continua GET, é só um teste rápido)

Detalhes e exemplos de cada um em [`server/README.md`](server/README.md).

## 2. App Android (`app/`)

Projeto Android Studio (Kotlin) já dentro deste repositório. Abra a pasta raiz
`GreenNotify` no Android Studio e deixe o Gradle sincronizar.

### Uso

1. Abra o app, preencha:
   - **URL do servidor**: ex. `http://192.168.0.10:8080` (IP da máquina onde o servidor roda)
   - **ID do dispositivo**: o mesmo `deviceId` usado no `/register`
   - **Chave de API**: a `apiKey` retornada pelo `/register`
2. Toque em **Salvar configuração**.
3. Toque em **Iniciar conexão** — isso sobe um serviço em primeiro plano (notificação
   discreta e permanente) que mantém o WebSocket conectado ao servidor, com reconexão
   automática caso a conexão caia.
4. Pronto: qualquer `POST /notify` feito para esse `deviceId` aparece como notificação
   nativa no celular, mostrando título, mensagem, motivo, origem e o link (se tiver).
5. Toque em **Ver notificações** pra abrir o histórico: dá pra filtrar entre Pendentes,
   Concluídas e Arquivadas, ver os detalhes completos de cada uma, abrir o link e marcar
   como concluída ou arquivar direto pela lista.

O app pede a permissão de notificações (Android 13+) na primeira abertura.

### Modos de recebimento

- **Tempo real** (padrão): conexão aberta com o servidor, a notificação chega na hora.
  Consome pouco: o app manda um sinal de vida a cada 3 minutos e o servidor só pinga quem
  ficou calado. Tem uma notificação fixa discreta (dá pra esconder: toque e segure nela e
  desative "Conexão em segundo plano"). Em celulares Samsung/Xiaomi, toque em "Liberar em
  segundo plano" para o sistema não matar a conexão.
- **Economia**: sem conexão aberta nem notificação fixa. O app consulta o servidor a cada
  ~15 minutos (mínimo do Android) e mostra o que chegou. Consumo quase zero, mas com atraso.

## 3. Integrando suas outras aplicações

Qualquer aplicação (script, backend, IoT, cron job) só precisa fazer um `POST` com JSON,
sem precisar expor um endpoint de webhook — funciona de qualquer linguagem (Python
`requests.post(...)`, PHP `curl`, PowerShell `Invoke-RestMethod`, etc). Exemplos prontos
em cada linguagem estão em [`server/README.md`](server/README.md).

## Segurança (importante)

- Comunicação é **HTTP puro** (a pedido) — as chaves de API trafegam em texto claro. Use
  isso apenas em rede local/VPN confiável, nunca exposto diretamente à internet pública.
- Cada dispositivo tem sua própria `apiKey`, e cadastro de novos dispositivos exige a
  `ADMIN_KEY` do servidor — troque o valor padrão via variável de ambiente.
- Notificações ficam persistidas (SQLite) até serem apagadas, e mantêm um `status`
  (pendente/concluída/arquivada) pra você organizar o histórico.
