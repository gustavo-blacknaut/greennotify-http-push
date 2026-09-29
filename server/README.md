# GreenNotify - servidor

Servidor simples em Node.js que recebe pedidos de notificação via POST (sem
webhook, sem HTTPS obrigatório) e manda pro app Android via WebSocket. Guarda
tudo num banco SQLite local (`better-sqlite3`, sem precisar instalar banco
nenhum separado). Feito pra rodar em qualquer VPS ou painel tipo Pterodactyl
mesmo.

## Rodando local pra testar

```bash
cd server
npm install
ADMIN_KEY=troque-por-uma-chave-longa PORT=8080 node index.js
```

A `ADMIN_KEY` é obrigatória e precisa ter pelo menos 16 caracteres — sem ela o
servidor nem sobe.

Depois é só testar no navegador:

```
http://localhost:8080/health
```

Se voltar `{"ok":true}` tá rodando.

## Subindo no Pterodactyl

O painel só precisa dos arquivos da pasta `server/`, não do resto do repositório
(o app Android não tem nada a ver com isso).

1. No painel, cria um servidor novo usando o egg **Generic Node.js** (ou "Node.js"
   dependendo de como tá nomeado no seu Pterodactyl). Escolhe uma versão do Node
   18 ou mais nova.
2. Depois que o servidor for criado, entra no File Manager dele (ou conecta via
   SFTP, é mais rápido pra mandar vários arquivos).
3. Manda pra dentro do `/home/container` só o que tá dentro de `server/`:
   - `index.js`
   - `store.js`
   - `package.json`
   - `package-lock.json`
   - `.npmrc` (importante, ver passo 6)
   - (não precisa mandar `node_modules` nem `data.sqlite`, se tiver)
4. Na aba **Startup** do servidor, configura:
   - **Startup Command**: `node index.js`
   - Se o egg pedir "Main File" ou algo assim, coloca `index.js`
5. Na aba **Variáveis** (Startup também, geralmente), cria/edita:
   - `ADMIN_KEY` → coloca uma chave forte, é ela que autoriza cadastrar novo
     dispositivo no `/register`. Não deixa o valor padrão do código.
   - Se o egg tiver variável de porta (tipo `SERVER_PORT` ou `PORT`), confere se
     bate com a porta alocada pro seu servidor no Pterodactyl. Se não tiver, o
     código já lê `process.env.PORT`, então só precisa garantir que a porta que o
     Pterodactyl abriu é a mesma passada nessa variável.
6. Antes de iniciar, roda o `npm install` — geralmente dá pra fazer isso direto
   no console do Pterodactyl, ou usando a opção de "Install" do egg se ele já
   rodar isso automaticamente. **Manda o `.npmrc` junto** com os outros
   arquivos (ele começa com ponto, então alguns clientes SFTP escondem — confere
   se subiu).

   Por quê: o `better-sqlite3` já vem com o binário pronto pra Linux, Windows e
   Mac, mas por padrão o npm tenta recompilar ele do zero na instalação. Isso
   exige Python e compilador C++, que muitos eggs de Node não têm — aí o
   `npm install` falha com `gyp ERR! find Python`. O `.npmrc` tem
   `ignore-scripts=true`, que pula essa recompilação desnecessária e usa o
   binário pronto. Se por algum motivo não der pra mandar o `.npmrc`, o mesmo
   efeito sai com `npm install --ignore-scripts`.
7. Start no servidor. Se der tudo certo, o log mostra:

   ```
   GreenNotify server rodando em http://0.0.0.0:PORTA (HTTP puro, sem HTTPS)
   ```

8. Testa de fora acessando `http://IP_DO_SEU_VPS:PORTA/health` pra confirmar que
   a porta tá liberada no firewall do painel/host.

## Cadastrando o celular

Antes de mandar notificação, precisa cadastrar o dispositivo uma vez só. Isso
gera a `apiKey` que você vai usar tanto no app quanto nos seus scripts.

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/register" \
  -H "Content-Type: application/json" \
  -d '{"adminKey":"SUA_ADMIN_KEY","deviceId":"meu-celular","name":"Meu Celular"}'
```

Guarda o `apiKey` que voltar na resposta, é ele que vai ser usado daqui pra
frente (não é a `adminKey`, essa só serve pra cadastrar dispositivo novo).

## Como mandar notificação de outra aplicação

Tudo aqui é POST com corpo em JSON — nada de query string, nada de webhook
entrando na sua aplicação. É só a sua aplicação fazer a requisição quando
quiser mandar um aviso.

**curl:**

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/notify" \
  -H "Content-Type: application/json" \
  -d '{
    "key": "SUA_API_KEY",
    "deviceId": "meu-celular",
    "title": "Ticket #123 aberto",
    "message": "Cliente abriu um novo ticket de suporte",
    "reason": "Erro no pagamento reportado pelo cliente",
    "app": "Hostmine",
    "link": "https://discord.com/channels/123456789/987654321"
  }'
```

**Python:**

```python
import requests

requests.post("http://SEU_SERVIDOR:PORTA/notify", json={
    "key": "SUA_API_KEY",
    "deviceId": "meu-celular",
    "title": "Pedido novo",
    "message": "Chegou um pedido #4821",
    "reason": "Webhook da loja disparou esse evento",
    "app": "LojaOnline",
    "link": "https://minhaloja.com/pedidos/4821"
})
```

**PHP:**

```php
<?php
$ch = curl_init("http://SEU_SERVIDOR:PORTA/notify");
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_HTTPHEADER, ["Content-Type: application/json"]);
curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode([
    "key" => "SUA_API_KEY",
    "deviceId" => "meu-celular",
    "title" => "Erro no site",
    "message" => "Erro 500 detectado no checkout",
    "reason" => "Monitoramento automatico",
    "app" => "MeuSite",
    "link" => "https://status.meusite.com/incidentes/88"
]));
curl_exec($ch);
```

**PowerShell:**

```powershell
Invoke-RestMethod -Uri "http://SEU_SERVIDOR:PORTA/notify" -Method Post -ContentType "application/json" -Body (@{
    key = "SUA_API_KEY"
    deviceId = "meu-celular"
    title = "Script terminou"
    message = "Rodou sem erro"
    reason = "Tarefa agendada"
    app = "PowerShell"
} | ConvertTo-Json)
```

Os campos que dá pra mandar são esses:

| Campo      | Obrigatório | Pra que serve                                             |
|------------|:-----------:|-------------------------------------------------------------|
| `key`      | sim         | a apiKey do dispositivo (não é a adminKey)                   |
| `deviceId` | sim         | qual celular vai receber                                     |
| `title`    | não         | título da notificação                                        |
| `message`  | não         | o texto principal                                             |
| `reason`   | não         | o motivo, aparece destacado ("Motivo: ...")                  |
| `app`      | não         | nome de quem tá mandando, aparece como "Origem"               |
| `link`     | não         | um link (ticket, canal do Discord, pedido, etc) clicável no app |

Se o celular estiver com o app aberto/serviço rodando, a notificação chega na
hora. Se não estiver, fica guardada no servidor e é entregue assim que ele
reconectar — não precisa reenviar nada.

## Gerenciando as notificações (histórico, concluir, arquivar)

Toda notificação enviada fica salva no servidor com um `status`: `pending`
(pendente), `done` (concluída) ou `archived` (arquivada). O app usa esses
endpoints pra montar a tela de histórico, mas você também pode chamar
diretamente se quiser.

**Listar** (status é opcional, se omitir traz todas):

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/list" \
  -H "Content-Type: application/json" \
  -d '{"key":"SUA_API_KEY","deviceId":"meu-celular","status":"pending"}'
```

**Marcar como concluída:**

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/complete" \
  -H "Content-Type: application/json" \
  -d '{"key":"SUA_API_KEY","deviceId":"meu-celular","id":"ID_DA_NOTIFICACAO"}'
```

**Mover pra outro lugar** (arquivar, ou voltar pra pendente):

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/move" \
  -H "Content-Type: application/json" \
  -d '{"key":"SUA_API_KEY","deviceId":"meu-celular","id":"ID_DA_NOTIFICACAO","status":"archived"}'
```

**Apagar** (`id: "all"` apaga tudo do dispositivo):

```bash
curl -X POST "http://SEU_SERVIDOR:PORTA/delete" \
  -H "Content-Type: application/json" \
  -d '{"key":"SUA_API_KEY","deviceId":"meu-celular","id":"ID_DA_NOTIFICACAO"}'
```

## Sobre o data.sqlite

O servidor cria um arquivo `data.sqlite` (mais `data.sqlite-wal` e
`data.sqlite-shm`, do modo WAL do SQLite) na própria pasta, guardando os
dispositivos cadastrados e todas as notificações com seus status. É
persistente entre restarts, mas se você reinstalar o servidor do zero no
Pterodactyl (reinstall do egg) ele some junto — se precisar manter, faz backup
desses arquivos de vez em quando.

## Lembrete de segurança

Isso aqui é HTTP puro de propósito, então não vai colocar isso exposto direto
pra internet sem pensar duas vezes. Se o painel/VPS permitir, prefere deixar
atrás de VPN ou pelo menos restringe por IP no firewall. A chave de admin e as
chaves de cada dispositivo trafegam em texto puro, então trata isso como coisa
sensível mesmo sendo "só" uma chave de notificação.

### Limitações do rate limit

Tem um limite contra quem tenta adivinhar chave: 20 respostas `401` (chave errada)
em 15 minutos bloqueiam o IP com `429` até a janela passar, e o `/register` aceita
10 tentativas a cada 15 minutos. Erro de validação (`400`) não conta. Só que ele é
simples, e vale saber onde ele não ajuda:

- **Fica em memória.** Reiniciar o servidor zera a contagem de todo mundo.
- **É por IP.** Se várias aplicações suas rodam na mesma máquina e uma delas está
  com a chave errada, ela pode bloquear as outras (mesmo IP) por 15 minutos.
- **Atrás de proxy reverso (Nginx, Cloudflare, túnel etc.) todo mundo vira um IP só.**
  O servidor enxerga o IP do proxy, então um único cliente errando a chave bloqueia
  todos. Hoje não tem `trust proxy` configurado — se for colocar atrás de proxy,
  isso precisa ser ajustado no código antes.
