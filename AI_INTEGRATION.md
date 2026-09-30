# GreenNotify — guia de integração para IA

Este arquivo existe pra ser lido por uma IA de código (Claude, Copilot, etc.) quando o
usuário pedir algo como "integra as notificações do GreenNotify nessa aplicação" ou
"manda uma notificação pro meu celular quando X acontecer". Contém tudo que é preciso
saber pra gerar o código de integração sem precisar abrir o resto do repositório.

Se o usuário só disser "integra o GreenNotify", assuma que ele já tem:
- Um servidor GreenNotify rodando em algum lugar (peça a URL se não tiver sido informada)
- Um `deviceId` e uma `apiKey` já cadastrados (peça se não tiver sido informado — ele
  consegue esses dados chamando `/register` uma vez, ver seção abaixo)

Não é preciso instalar nenhuma biblioteca especial: é tudo `POST` com JSON, dá pra usar
o cliente HTTP que a linguagem/framework já tiver.

## O que é

Servidor HTTP (propositalmente sem HTTPS, pensado pra rede local/VPN) que recebe pedidos
de notificação de qualquer aplicação via `POST` e entrega pro app Android do dono via
WebSocket, guardando tudo num histórico com status (pendente/concluída/arquivada).

## Configuração necessária antes de integrar

Pergunte ao usuário (ou procure em variáveis de ambiente/config do projeto) por:

| Variável       | Exemplo                        | Obrigatório |
|----------------|---------------------------------|:-----------:|
| URL do servidor | `http://192.168.0.10:8080`     | sim |
| `deviceId`      | `meu-celular`                  | sim |
| `apiKey`        | `e29bb502b1c79df867c5ad...`    | sim |

Essas três informações não mudam entre chamadas — trate como configuração (env vars,
secrets, `.env`), nunca hardcode no código-fonte que vai pra um repositório público.

A `adminKey` é a variável de ambiente `ADMIN_KEY` do servidor: obrigatória e com pelo
menos 16 caracteres — sem ela o servidor nem sobe. Se o usuário estiver configurando o
servidor, lembre disso.

Se o `apiKey` ainda não existir, é preciso cadastrar o dispositivo uma única vez com a
`adminKey` do servidor (isso normalmente já foi feito pelo dono antes; só faça essa
chamada se o usuário pedir explicitamente pra cadastrar um novo dispositivo):

```bash
curl -X POST "http://SERVIDOR:PORTA/register" \
  -H "Content-Type: application/json" \
  -d '{"adminKey":"ADMIN_KEY","deviceId":"meu-celular","name":"Nome bonito"}'
```

Resposta:
```json
{ "deviceId": "meu-celular", "apiKey": "e29bb502b1c79df...", "name": "Nome bonito" }
```

## Enviar uma notificação — o endpoint que importa

```
POST {SERVIDOR}/notify
Content-Type: application/json
```

Corpo:

```json
{
  "key": "APIKEY_DO_DISPOSITIVO",
  "deviceId": "meu-celular",
  "title": "Título curto",
  "message": "Texto principal da notificação",
  "reason": "Por que essa notificação está sendo enviada",
  "app": "Nome da sua aplicação",
  "link": "https://opcional.com/algum-lugar",
  "topic": "Assunto opcional para agrupar",
  "image": "https://opcional.com/imagem.jpg"
}
```

| Campo      | Tipo   | Obrigatório | Descrição |
|------------|--------|:-----------:|-----------|
| `key`      | string | sim | apiKey do dispositivo (não é a adminKey) |
| `deviceId` | string | sim | identifica qual celular recebe |
| `title`    | string | não | título mostrado em negrito na notificação |
| `message`  | string | não | corpo principal da mensagem |
| `reason`   | string | não | aparece como "Motivo: ..." — use pra explicar o porquê do alerta, não repita o `message` |
| `app`      | string | não | aparece como "Origem" — normalmente o nome da aplicação/serviço que está chamando |
| `link`     | string | não | URL completa (com `https://` ou `http://`). Se enviado, tocar na notificação ou no item da lista abre esse link direto |
| `topic`    | string | não | Assunto. Notificações com o mesmo `topic` ficam empilhadas numa entrada só no celular (use um identificador estável: `"Ticket #123"`, `"Pedido #55"`, `"Backup diário"`). Sem `topic`, o app agrupa pelo `app` |
| `image`    | string | não | URL http(s) de uma imagem. Aparece grande na notificação (Android) e no modal de detalhes ao tocar. Prefira imagens leves (até ~1 MB) |

Resposta (200):

```json
{
  "ok": true,
  "delivered": false,
  "notification": {
    "id": "ad38053d704e4588",
    "deviceId": "meu-celular",
    "title": "Título curto",
    "message": "Texto principal da notificação",
    "reason": "Por que essa notificação está sendo enviada",
    "app": "Nome da sua aplicação",
    "link": "https://opcional.com/algum-lugar",
    "status": "pending",
    "delivered": false,
    "createdAt": 1790623696444
  }
}
```

`delivered: true` significa que o celular estava conectado e recebeu na hora via
WebSocket. `delivered: false` só quer dizer que ele estava offline — a notificação
**não se perde**, fica guardada e chega assim que o app reconectar. Não é necessário
reenviar.

Codificação: mande o corpo em UTF-8 (`Content-Type: application/json; charset=utf-8`). O servidor também aceita Windows-1252 (padrão do PowerShell 5) e converte, então acentos chegam certos nos dois casos.

Erros possíveis:
- `401 { "error": "deviceId ou key inválidos" }` — key errada ou deviceId não cadastrado
- Conexão recusada / timeout — servidor fora do ar ou URL/porta erradas

## Outros endpoints (só use se o usuário pedir esse tipo de funcionalidade)

Todos exigem `key` e `deviceId` no corpo, iguais ao `/notify`.

**Listar notificações** (pra montar um painel, dashboard, bot que consulta o histórico, etc):

```
POST {SERVIDOR}/list
{ "key": "...", "deviceId": "...", "status": "pending", "limit": 100, "offset": 0 }
```
- `status` é opcional (`pending` | `done` | `archived`); se omitido, retorna todos os status.
- A lista é **paginada**, mais recentes primeiro. `limit` padrão 100, máximo 500;
  `offset` padrão 0. Valores fora disso voltam 400.
- Pra buscar tudo, repita aumentando `offset` de `limit` em `limit` até vir uma página
  com menos itens que o `limit`. Não assuma que uma chamada sem `limit` traz o
  histórico inteiro — traz no máximo 100.

Resposta: `{ "notifications": [ {...}, {...} ] }` (mesmo formato do objeto acima).

**Marcar como concluída:**
```
POST {SERVIDOR}/complete
{ "key": "...", "deviceId": "...", "id": "ad38053d704e4588" }
```

**Mover pra outro status (ex: arquivar):**
```
POST {SERVIDOR}/move
{ "key": "...", "deviceId": "...", "id": "ad38053d704e4588", "status": "archived" }
```

**Apagar** (`id: "all"` apaga tudo do dispositivo):
```
POST {SERVIDOR}/delete
{ "key": "...", "deviceId": "...", "id": "ad38053d704e4588" }
```

**Healthcheck** (único endpoint que é GET, sem autenticação):
```
GET {SERVIDOR}/health  →  { "ok": true }
```

## Como gerar o código de integração

O padrão é sempre: pegar as três variáveis de configuração de algum lugar seguro
(env var / secrets manager do projeto do usuário) e fazer um `POST` pro `/notify`
no momento certo do fluxo (erro capturado, evento de negócio concluído, alerta
disparado, etc). Não é preciso reter estado nem tratar resposta além de logar
falha — a aplicação do usuário não deve quebrar se o GreenNotify estiver fora do
ar, então trate a chamada como best-effort (não deixe uma falha de notificação
derrubar o fluxo principal).

Exemplo mínimo em cada stack comum:

**Node.js (fetch nativo):**
```js
async function notify({ title, message, reason, app, link }) {
  try {
    await fetch(`${process.env.GREENNOTIFY_URL}/notify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        key: process.env.GREENNOTIFY_KEY,
        deviceId: process.env.GREENNOTIFY_DEVICE,
        title, message, reason, app, link
      })
    });
  } catch (e) {
    console.error("Falha ao notificar GreenNotify:", e);
  }
}
```

**Python:**
```python
import os, requests

def notify(title="", message="", reason="", app="", link=""):
    try:
        requests.post(f"{os.environ['GREENNOTIFY_URL']}/notify", json={
            "key": os.environ["GREENNOTIFY_KEY"],
            "deviceId": os.environ["GREENNOTIFY_DEVICE"],
            "title": title, "message": message,
            "reason": reason, "app": app, "link": link
        }, timeout=5)
    except requests.RequestException as e:
        print(f"Falha ao notificar GreenNotify: {e}")
```

**PHP:**
```php
function greennotify_notify(string $title, string $message, string $reason = '', string $app = '', string $link = ''): void {
    $ch = curl_init(getenv('GREENNOTIFY_URL') . '/notify');
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json']);
    curl_setopt($ch, CURLOPT_TIMEOUT, 5);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode([
        'key' => getenv('GREENNOTIFY_KEY'),
        'deviceId' => getenv('GREENNOTIFY_DEVICE'),
        'title' => $title, 'message' => $message,
        'reason' => $reason, 'app' => $app, 'link' => $link,
    ]));
    curl_exec($ch); // best-effort, não trava o fluxo principal se falhar
}
```

Adapte o padrão acima pra outras linguagens: sempre POST JSON pro `/notify`, sempre
best-effort (não propague exceção pro chamador), sempre puxando as três credenciais
de variáveis de ambiente/config, nunca hardcoded.

## O que NÃO fazer

- Não hardcodar `key`/`adminKey` em código versionado — sempre variável de ambiente
- Não usar `GET` — o servidor não aceita mais GET em nenhum endpoint de escrita
- Não tratar falha de notificação como erro fatal da aplicação principal
- Não confundir `key` (apiKey do dispositivo, usada toda hora) com `adminKey`
  (só usada uma vez, pra cadastrar dispositivo novo)
- Não montar a URL com HTTPS — o servidor é HTTP puro por design; se o usuário
  colocar `https://` na URL configurada, avise que provavelmente está errado
