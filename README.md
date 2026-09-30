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
- `image` — link (http/https) de uma imagem opcional: aparece no círculo da notificação e do cartão, e grande no modal.
- `category` — categoria (pasta) no app. Até 4 por celular; se não existir e houver vaga, é criada na hora.
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
- `/categories/list`, `/categories/create`, `/categories/update`, `/categories/delete`, `/categories/clear` — as pastas (até 4)
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

### Tela do app

- A tela inicial já é a lista de notificações, com filtros **Pendentes / Concluídas /
  Arquivadas**, puxar para atualizar e rolagem infinita.
- **Arraste para a direita** para arquivar e **para a esquerda** para apagar. Os dois
  mostram "Desfazer" por alguns segundos, e a exclusão só vai para o servidor depois disso.
- Cada notificação tem botões para abrir o link, concluir (ou reabrir) e arquivar (ou
  restaurar).
- O cartão verde no topo mostra a rede atual e como está recebendo, e tem o botão Iniciar/Parar.
  Servidor, chave e o modo de cada rede ficam na engrenagem (Configurações), que também tem
  "Testar conexão".

### Como receber: cada rede com a sua regra

Em **Configurações › Como receber** você escolhe, separadamente para **Wi‑Fi** e para **dados móveis**,
o que o app faz:

- **Tempo real:** conexão aberta, a notificação chega na hora. Você escolhe de quanto em quanto
  tempo o app manda um "sinal de vida" (1 a 15 min; padrão 5 min). Intervalo maior gasta menos
  bateria, mas demora mais para notar que a conexão caiu.
- **Verificar de tempos em tempos:** sem conexão aberta. O app consulta o servidor a cada
  2, 5, 10, 15, 30 ou 60 minutos e mostra o que chegou (mínimo do Android para tarefas em
  segundo plano; com o celular parado e a tela apagada o sistema pode atrasar um pouco).
- **Desligado:** não recebe nessa rede.

Atalhos de um toque: **Só tempo real**, **Tempo real no Wi‑Fi + verificar nos dados** e **Só
economia**. Ao trocar de Wi‑Fi para dados (ou o contrário), o app reavalia na hora: abre ou fecha a
conexão e, se a nova rede for de "verificar", já faz uma verificação.

Abaixo de cada rede aparece o **custo estimado** (acordadas do rádio por dia, MB por mês e impacto).
O que pesa na bateria é o número de vezes que o rádio do celular acorda, não o volume de dados.
Números medidos: uma verificação = 1 acordada e ~1,2 KB; um sinal de vida = 1 acordada e ~0,2 KB;
receber uma notificação em tempo real = ~0,3 KB.

### Tela de consumo

No ícone de gráfico (topo da tela inicial) ou em Configurações › Ver consumo: para hoje e para os
últimos 7 dias, separado em Wi‑Fi e dados móveis, mostra o tráfego real do app (contador do próprio
Android, com cabeçalhos), quantas verificações foram feitas, quanto tempo a conexão ficou aberta e
quantas notificações chegaram, mais a estimativa da configuração atual. O botão **Ver bateria no
sistema** abre a tela do Android com o consumo real em mAh/% do app: compare depois de alguns dias em
cada configuração.

Em Samsung/Xiaomi, use **Liberar em segundo plano** nas configurações para o sistema não derrubar a
conexão de tempo real.

### Aviso fixo de pendentes e lembrete

Enquanto houver notificações pendentes (não concluídas nem arquivadas), um aviso fixo fica
no topo das notificações com prioridade máxima: "3 notificações pendentes · Última: ...".
No tempo real ele fica verde, como o do Spotify, e no Android 16 o app pede para destacá-lo
como Live Update (a Samsung costuma mostrar na tela de bloqueio/Now Bar). A cada 10 minutos,
se ainda houver pendentes, ele toca de novo — dá para desligar em Configurações ›
"Lembrar a cada 10 minutos".

O tempo real volta sozinho depois de reiniciar o celular e depois de instalar uma
atualização do app.

### Categorias (pastas)

Na tela inicial ficam até 4 pastas com imagem e nome, com o número de pendentes em cada uma.
Tocar numa pasta mostra só as notificações dela (o filtro fica no topo mesmo rolando a lista);
tocar de novo, ou no X, volta para todas. Segurando a pasta: editar nome/imagem, criar uma
notificação nela, apagar todas as notificações dela ou apagar a categoria (com ou sem as notificações).
O botão **Nova** cria uma notificação pelo próprio celular.

## 3. Integrando suas outras aplicações

Em Node.js, copie [`clients/node/greennotify.js`](clients/node/greennotify.js) para o projeto — é só
configurar o `.env` e chamar `notificar({...})`.

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
