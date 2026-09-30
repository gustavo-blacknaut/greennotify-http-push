// Cliente do GreenNotify para Node.js (ES module, sem dependências, funciona do Node 14 em diante).
//
// Copie este arquivo para o projeto e configure no .env:
//   GREENNOTIFY_URL=http://seu-servidor:porta
//   GREENNOTIFY_DEVICE=zflip
//   GREENNOTIFY_KEY=apiKey-do-dispositivo
//   GREENNOTIFY_CATEGORY=NomeDaCategoria   (opcional: pasta no app)
//   GREENNOTIFY_APP=NomeDoProjeto          (opcional: aparece como "Origem")
//
// Uso:
//   import { notificar, agrupar } from './greennotify.js';
//   notificar({ title: 'Pedido pago', message: 'R$ 49,90', topic: 'Pedido #12', link: 'https://...' });
//   vigiar('Meu Bot');   // avisa no celular se o processo desligar, travar ou cair
//
// Nunca lança erro nem trava quem chama: se o servidor estiver fora, tenta mais 2 vezes e desiste.
// Sem GREENNOTIFY_URL/DEVICE/KEY no ambiente, não faz nada.

import http from 'node:http';
import https from 'node:https';

const TENTATIVAS = [0, 15000, 60000];

function configurado() {
    return !!(process.env.GREENNOTIFY_URL && process.env.GREENNOTIFY_DEVICE && process.env.GREENNOTIFY_KEY);
}

function postar(corpo, caminho = '/notify') {
    return new Promise((resolve) => {
        let url;
        try {
            url = new URL(caminho, process.env.GREENNOTIFY_URL);
        } catch (_) {
            return resolve({ ok: false, final: true, erro: 'GREENNOTIFY_URL inválida' });
        }
        const dados = Buffer.from(JSON.stringify(corpo), 'utf8');
        const req = (url.protocol === 'https:' ? https : http).request(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': dados.length },
            timeout: 8000,
        }, (res) => {
            let texto = '';
            res.setEncoding('utf8');
            res.on('data', (c) => { texto += c; });
            res.on('end', () => {
                // 4xx = pedido errado (chave, campo grande demais): tentar de novo não resolve.
                const final = res.statusCode >= 400 && res.statusCode < 500;
                resolve({ ok: res.statusCode === 200, final, erro: res.statusCode === 200 ? null : `HTTP ${res.statusCode} ${texto.slice(0, 200)}` });
            });
        });
        req.on('timeout', () => req.destroy(new Error('tempo esgotado')));
        req.on('error', (e) => resolve({ ok: false, final: false, erro: e.message }));
        req.end(dados);
    });
}

const LIMITES = { title: 200, message: 4000, reason: 1000, app: 100, link: 2000, topic: 100, image: 2000, category: 40 };

/**
 * Envia uma notificação. Campos de texto: title, message, reason, link, topic, image, category, app.
 * Opcionais: tapAction ('details' | 'link' | 'link_done'), priority ('normal' | 'alarm'),
 * unique (true = uma notificação pendente por topic; as novas entram nela) e count (quantos itens
 * esta chamada soma; "{n}" no title vira o total).
 * Retorna uma Promise<boolean> (true = o servidor aceitou). Pode ser chamada sem await.
 */
export async function notificar(campos) {
    if (!configurado()) return false;
    const corpo = {
        key: process.env.GREENNOTIFY_KEY,
        deviceId: process.env.GREENNOTIFY_DEVICE,
        app: process.env.GREENNOTIFY_APP || undefined,
        category: process.env.GREENNOTIFY_CATEGORY || undefined,
    };
    for (const [campo, valor] of Object.entries(campos || {})) {
        if (valor === undefined || valor === null || valor === '') continue;
        // Números e true/false vão como estão (count, unique); o resto vira texto.
        if (typeof valor === 'number' || typeof valor === 'boolean') { corpo[campo] = valor; continue; }
        let texto = String(valor);
        const max = LIMITES[campo];
        if (max && texto.length > max) texto = texto.slice(0, max - 1) + '…';
        corpo[campo] = texto;
    }
    // link e image só são aceitos com http(s)
    for (const campo of ['link', 'image']) {
        if (corpo[campo] && !/^https?:\/\//i.test(corpo[campo])) delete corpo[campo];
    }

    for (const espera of TENTATIVAS) {
        if (espera) await new Promise((r) => setTimeout(r, espera));
        const r = await postar(corpo);
        if (r.ok) return true;
        if (r.final) {
            console.error('[greennotify] notificação recusada:', r.erro);
            return false;
        }
    }
    console.error('[greennotify] servidor fora do ar, notificação descartada:', corpo.title);
    return false;
}

const grupos = new Map();

/**
 * Junta vários eventos da mesma [chave] que chegam em sequência numa notificação só.
 * O primeiro evento abre uma janela de [ms]; ao fim dela, [enviar] recebe a lista de itens.
 * Ex.: várias mensagens seguidas no mesmo ticket viram "3 mensagens novas" em vez de 3 avisos.
 */
export function agrupar(chave, item, ms, enviar) {
    const grupo = grupos.get(chave);
    if (grupo) {
        grupo.itens.push(item);
        return;
    }
    const novo = { itens: [item] };
    grupos.set(chave, novo);
    setTimeout(() => {
        grupos.delete(chave);
        Promise.resolve(enviar(novo.itens)).catch((e) => console.error('[greennotify] erro ao agrupar:', e));
    }, ms);
}

/**
 * Vigia: manda um sinal de vida a cada [intervalo] segundos. Se o processo parar de mandar
 * (caiu, travou, a hospedagem desligou), o servidor avisa o celular: "🔴 [nome] parou de responder".
 * Desligar pelo painel (SIGINT/SIGTERM) avisa na hora: "⏹️ [nome] foi desligado".
 * Ligar normalmente NÃO gera notificação; só a volta depois de uma queda ("✅ [nome] voltou").
 */
export function vigiar(nome, { intervalo = 60, image, category } = {}) {
    if (!configurado()) return;
    const corpo = (extra) => ({
        key: process.env.GREENNOTIFY_KEY,
        deviceId: process.env.GREENNOTIFY_DEVICE,
        name: nome,
        interval: intervalo,
        category: category || process.env.GREENNOTIFY_CATEGORY || undefined,
        image: typeof image === 'function' ? image() : image,
        ...extra,
    });
    const sinal = () => postar(corpo(), '/heartbeat').then((r) => {
        if (!r.ok && r.final) console.error('[greennotify] vigia recusado:', r.erro);
    });
    sinal();
    const timer = setInterval(sinal, intervalo * 1000);
    timer.unref?.(); // o vigia sozinho não segura o processo aberto

    let saindo = false;
    const desligar = (codigo) => {
        if (saindo) return;
        saindo = true;
        clearInterval(timer);
        // Espera no máximo 3 s pelo aviso e sai de qualquer jeito.
        const fim = setTimeout(() => process.exit(codigo), 3000);
        postar(corpo({ stopping: true }), '/heartbeat').finally(() => { clearTimeout(fim); process.exit(codigo); });
    };
    process.once('SIGINT', () => desligar(0));
    process.once('SIGTERM', () => desligar(0));
}
