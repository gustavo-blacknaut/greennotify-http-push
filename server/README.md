# GreenNotify - servidor

Servidor simples em Node.js que recebe pedidos de notificação via GET (sem webhook,
sem HTTPS obrigatório) e manda pro app Android via WebSocket. Feito pra rodar em
qualquer VPS ou painel tipo Pterodactyl mesmo.

## Rodando local pra testar

```bash
cd server
npm install
ADMIN_KEY=troque-isso PORT=8080 node index.js
```

Depois é só testar com curl ou no navegador:

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
   - (não precisa mandar `node_modules` nem `data.json`, se tiver)
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
   no console do Pterodactyl (ele deixa rodar comandos no terminal do servidor),
   ou usando a opção de "Install" do egg se ele já rodar isso automaticamente.
7. Start no servidor. Se der tudo certo, o log mostra:

   ```
   GreenNotify server rodando em http://0.0.0.0:PORTA (HTTP puro, sem HTTPS)
   ```

8. Testa de fora acessando `http://IP_DO_SEU_VPS:PORTA/health` pra confirmar que
   a porta tá liberada no firewall do painel/host.

## Cadastrando o celular

Antes de mandar notificação, precisa cadastrar o dispositivo uma vez só. Isso
gera a `apiKey` que você vai usar tanto no app quanto nos seus scripts.

```
http://SEU_SERVIDOR:PORTA/register?adminKey=SUA_ADMIN_KEY&deviceId=meu-celular&name=Meu+Celular
```

Guarda o `apiKey` que voltar na resposta, é ele que vai ser usado daqui pra
frente (não é a `adminKey`, essa só serve pra cadastrar dispositivo novo).

## Como mandar notificação de outra aplicação

É só fazer um GET simples pro endpoint `/notify`, passando a `apiKey` do
dispositivo, o `deviceId` e o que você quer mostrar. Não precisa de biblioteca
nenhuma, funciona de qualquer linguagem que consiga fazer uma requisição HTTP.

**Direto no navegador ou com curl**, só pra testar:

```
http://SEU_SERVIDOR:PORTA/notify?key=SUA_API_KEY&deviceId=meu-celular&title=Teste&message=Deu+certo&reason=Testando+o+servidor&app=Manual
```

**curl** (Linux/Mac/Git Bash):

```bash
curl "http://SEU_SERVIDOR:PORTA/notify?key=SUA_API_KEY&deviceId=meu-celular&title=Backup+concluido&message=Backup+diario+rodou+sem+erro&reason=Rotina+agendada&app=BackupScript"
```

**Python:**

```python
import requests

requests.get("http://SEU_SERVIDOR:PORTA/notify", params={
    "key": "SUA_API_KEY",
    "deviceId": "meu-celular",
    "title": "Pedido novo",
    "message": "Chegou um pedido #4821",
    "reason": "Webhook da loja disparou esse evento",
    "app": "LojaOnline"
})
```

**PHP:**

```php
<?php
$url = "http://SEU_SERVIDOR:PORTA/notify?" . http_build_query([
    "key" => "SUA_API_KEY",
    "deviceId" => "meu-celular",
    "title" => "Erro no site",
    "message" => "Erro 500 detectado no checkout",
    "reason" => "Monitoramento automatico",
    "app" => "MeuSite"
]);
file_get_contents($url);
```

**PowerShell** (se você tá num Windows rodando alguma tarefa agendada):

```powershell
Invoke-WebRequest "http://SEU_SERVIDOR:PORTA/notify?key=SUA_API_KEY&deviceId=meu-celular&title=Script+terminou&message=Rodou+sem+erro&reason=Tarefa+agendada&app=PowerShell"
```

**Cron job** (Linux), só pra ilustrar um uso real:

```bash
# manda notificação todo dia às 8h avisando que o backup rodou
0 8 * * * curl -s "http://SEU_SERVIDOR:PORTA/notify?key=SUA_API_KEY&deviceId=meu-celular&title=Backup&message=Rodou+as+8h&reason=Cron+diario&app=Backup" > /dev/null
```

Os parâmetros que dá pra mandar são esses:

| Parâmetro  | Obrigatório | Pra que serve                                    |
|------------|:-----------:|---------------------------------------------------|
| `key`      | sim         | a apiKey do dispositivo (não é a adminKey)         |
| `deviceId` | sim         | qual celular vai receber                           |
| `title`    | não         | título da notificação                              |
| `message`  | não         | o texto principal                                  |
| `reason`   | não         | o motivo, aparece destacado ("Motivo: ...")        |
| `app`      | não         | nome de quem tá mandando, aparece como "Origem"    |

Se o celular estiver com o app aberto/serviço rodando, a notificação chega na
hora. Se não estiver, fica guardada no servidor e é entregue assim que ele
reconectar — não precisa reenviar nada.

## Sobre o data.json

O servidor cria um arquivo `data.json` na própria pasta pra guardar os
dispositivos cadastrados e as notificações pendentes. Isso é persistente entre
restarts, mas se você reinstalar o servidor do zero no Pterodactyl (reinstall
do egg) ele some junto — se precisar manter, faz backup desse arquivo de vez
em quando.

## Lembrete de segurança

Isso aqui é HTTP puro de propósito, então não vai colocar isso exposto direto
pra internet sem pensar duas vezes. Se o painel/VPS permitir, prefere deixar
atrás de VPN ou pelo menos restringe por IP no firewall. A chave de admin e as
chaves de cada dispositivo trafegam em texto puro, então trata isso como coisa
sensível mesmo sendo "só" uma chave de notificação.
