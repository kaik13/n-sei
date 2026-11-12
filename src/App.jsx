import React, { useCallback, useEffect, useRef, useState } from 'react';
import { version as APP_VERSION } from '../package.json';
import {
  AlertCircle, ArrowDownToLine, ArrowRight, ArrowUpRight, Check, CheckCircle2,
  ChevronRight, CircleHelp, Cloud, CloudOff, Copy, ExternalLink, FileArchive,
  Gamepad2, Globe2, HardDrive, LayoutDashboard, LifeBuoy, LoaderCircle, LockKeyhole,
  LogOut, Map, MoreHorizontal, PackagePlus, Power, Puzzle, RefreshCw, Server,
  ShieldCheck, Smartphone, Sparkles, TerminalSquare, Trash2, Upload, UsersRound, X,
} from 'lucide-react';

const EMPTY = {
  dockerAvailable: false, panelConfigured: false, hostError: null,
  server: { exists: false, running: false, state: 'not_created', name: 'Meu servidor', address: null, port: 19132, players: null, maxPlayers: 10, version: null, mode: 'survival', difficulty: 'normal' },
  world: { exists: false, name: null }, packs: [], hostHint: '',
};
const STATUS = {
  not_created: ['Não configurado', 'neutral'],
  unknown: ['Hospedagem desconectada', 'neutral'],
  starting: ['Iniciando', 'pending'],
  online: ['Online', 'online'],
  offline: ['Desligado', 'neutral'],
  error: ['Precisa de atenção', 'danger'],
};
const MODE = { survival: 'Sobrevivência', creative: 'Criativo', adventure: 'Aventura' };
const DIFFICULTY = { peaceful: 'Pacífico', easy: 'Fácil', normal: 'Normal', hard: 'Difícil' };

function Brand({ compact = false }) {
  return <div className={`brand ${compact ? 'brand-compact' : ''}`}>
    <img className="brand-mark" src="/icons/mark.svg" alt="" />
    <div className="brand-copy"><span className="brand-name">bloco<span>.</span></span><span className="brand-caption">SEU MUNDO, SEU JEITO</span></div>
  </div>;
}

function Modal({ title, subtitle, onClose, children, wide = false, busy = false }) {
  const ref = useRef(null);
  const closeRef = useRef(onClose);
  const busyRef = useRef(busy);
  closeRef.current = onClose;
  busyRef.current = busy;
  useEffect(() => {
    const previous = document.activeElement;
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const frame = requestAnimationFrame(() => {
      if (!ref.current?.contains(document.activeElement)) ref.current?.querySelector('input:not([type="hidden"]), button, select')?.focus();
    });
    const keydown = (event) => {
      if (event.key === 'Escape' && !busyRef.current) closeRef.current();
      if (event.key === 'Tab') {
        const focusable = [...(ref.current?.querySelectorAll('button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), a[href], textarea:not([disabled])') || [])];
        if (!focusable.length) return;
        if (event.shiftKey && document.activeElement === focusable[0]) { event.preventDefault(); focusable.at(-1).focus(); }
        else if (!event.shiftKey && document.activeElement === focusable.at(-1)) { event.preventDefault(); focusable[0].focus(); }
      }
    };
    document.addEventListener('keydown', keydown);
    return () => { cancelAnimationFrame(frame); document.body.style.overflow = oldOverflow; document.removeEventListener('keydown', keydown); previous?.focus?.(); };
  }, []);
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby="modal-title" ref={ref}>
      <div className="modal-header">
        <div><h2 id="modal-title">{title}</h2>{subtitle && <p>{subtitle}</p>}</div>
        <button className="icon-button close-button" aria-label="Fechar" onClick={onClose} disabled={busy}><X size={20} /></button>
      </div>
      {children}
    </div>
  </div>;
}

function StatusPill({ state }) {
  const [text, color] = STATUS[state] || STATUS.unknown;
  return <span className={`status-pill ${color}`}><span className="status-dot" />{text}</span>;
}

function SideNav({ active, navigate, openHelp, installPrompt, install }) {
  const nav = [
    ['inicio', LayoutDashboard, 'Visão geral'],
    ['servidor', Server, 'Meu servidor'],
    ['mundo', Map, 'Meu mundo'],
    ['conteudos', Puzzle, 'Add-ons e texturas'],
  ];
  return <>
    <aside className="sidebar">
      <Brand />
      <div className="nav-label">MENU PRINCIPAL</div>
      <nav className="side-nav" aria-label="Navegação principal">
        {nav.map(([id, Icon, label]) => <button key={id} className={`nav-item ${active === id ? 'active' : ''}`} onClick={() => navigate(id)}><Icon size={19} strokeWidth={1.8} /><span>{label}</span>{active === id && <span className="nav-active-dot" />}</button>)}
      </nav>
      <div className="sidebar-spacer" />
      {installPrompt && <button className="side-install" onClick={install}><Smartphone size={17} /> Instalar aplicativo <ArrowUpRight size={15} /></button>}
      <div className="side-help-card">
        <div className="side-help-icon"><Cloud size={19} strokeWidth={1.8} /><span className="side-spark">✳</span></div>
        <strong>Seu mundo, mais perto.</strong>
        <p>Cuide do seu servidor de onde estiver.</p>
        <button onClick={openHelp}>Saiba como <ArrowRight size={14} /></button>
      </div>
      <div className="side-footer"><span className="tiny-cube">◆</span> FEITO PARA BEDROCK <span>·</span> {APP_VERSION}</div>
    </aside>
    <nav className="mobile-nav" aria-label="Navegação principal">
      {nav.map(([id, Icon, label]) => <button key={id} className={active === id ? 'active' : ''} onClick={() => navigate(id)}><Icon size={20} strokeWidth={1.8} /><span>{id === 'inicio' ? 'Início' : id === 'servidor' ? 'Servidor' : id === 'mundo' ? 'Mundo' : 'Add-ons'}</span><span className="sr-only">{label}</span></button>)}
    </nav>
  </>;
}

function HelpContent({ onClose, hostAvailable, panelConfigured, installPrompt, install }) {
  return <>
    <div className="help-intro"><div className="help-intro-icon"><Cloud size={22} /></div><div><strong>O celular é o controle. A hospedagem mantém o jogo no ar.</strong><p>O Bloco é um painel, não é um serviço de hospedagem. Para jogar 24h, seu servidor precisa ficar em uma máquina sempre ligada e conectada à internet.</p></div></div>
    <div className="help-steps">
      <div><span>01</span><p><strong>Prepare uma hospedagem</strong>Use uma máquina Linux com Docker, armazenamento persistente e IPv4 acessível. Uma hospedagem na nuvem pode ter custos.</p></div>
      <div><span>02</span><p><strong>Instale e proteja o painel</strong>Configure uma chave de acesso, publique o painel com HTTPS e siga as instruções do README do projeto. Essa instalação inicial exige acesso ao terminal da hospedagem.</p></div>
      <div><span>03</span><p><strong>Libere as portas e jogue</strong>Abra TCP 19132 e UDP 19140–19155 no firewall. Depois, crie seu servidor e compartilhe o IP e a porta com os amigos.</p></div>
    </div>
    <div className="help-status"><span><span className={`little-dot ${hostAvailable ? 'is-on' : ''}`} />Hospedagem {hostAvailable ? 'conectada' : 'não conectada'}</span><span><span className={`little-dot ${panelConfigured ? 'is-on' : ''}`} />Chave {panelConfigured ? 'configurada' : 'não configurada'}</span></div>
    <p className="small-note">O servidor usa a versão estável mais recente disponível ao iniciar. A capacidade real depende dos recursos da hospedagem, dos jogadores e dos add-ons.</p>
    <div className="modal-actions">{installPrompt && <button className="button button-secondary" onClick={install}><Smartphone size={16} /> Instalar app</button>}<button className="button button-primary" onClick={onClose}>Entendi <Check size={16} /></button></div>
  </>;
}

function CreateForm({ status, working, onSubmit, onHelp, onClose }) {
  const [name, setName] = useState('Meu mundo');
  const [address, setAddress] = useState(status.hostHint || '');
  const [mode, setMode] = useState('survival');
  const [difficulty, setDifficulty] = useState('normal');
  const [acceptEula, setAcceptEula] = useState(false);
  const ready = status.dockerAvailable && status.panelConfigured && !status.hostError;
  return <form onSubmit={(event) => { event.preventDefault(); onSubmit({ name, address, mode, difficulty, acceptEula }); }}>
    {!ready && <div className="form-notice"><AlertCircle size={18} /><div><strong>Falta conectar sua hospedagem</strong><span>Você pode conhecer o formulário, mas precisa preparar o painel para criar o servidor.</span><button type="button" onClick={onHelp}>Ver o que é necessário <ArrowRight size={13} /></button></div></div>}
    <div className="form-grid">
      <label className="form-field full"><span>Nome do servidor</span><input value={name} onChange={(event) => setName(event.target.value)} maxLength={40} minLength={2} required placeholder="Ex.: Nosso mundo" /><small>Esse é o nome que vai aparecer no Minecraft.</small></label>
      <label className="form-field full"><span>IPv4 da hospedagem</span><input value={address} onChange={(event) => setAddress(event.target.value)} inputMode="decimal" autoCapitalize="none" autoCorrect="off" required placeholder="Ex.: 203.0.113.10" /><small>Use o IP da hospedagem, não o do seu celular.</small></label>
      <label className="form-field"><span>Modo de jogo</span><select value={mode} onChange={(event) => setMode(event.target.value)}><option value="survival">Sobrevivência</option><option value="creative">Criativo</option><option value="adventure">Aventura</option></select></label>
      <label className="form-field"><span>Dificuldade</span><select value={difficulty} onChange={(event) => setDifficulty(event.target.value)}><option value="peaceful">Pacífico</option><option value="easy">Fácil</option><option value="normal">Normal</option><option value="hard">Difícil</option></select></label>
    </div>
    <div className="settings-summary"><span><UsersRound size={16} /> Até 10 jogadores</span><span><Sparkles size={16} /> Última versão ao iniciar</span></div>
    <label className="check-field"><input type="checkbox" checked={acceptEula} onChange={(event) => setAcceptEula(event.target.checked)} required /><span>Li e aceito os <a href="https://www.minecraft.net/pt-br/eula" target="_blank" rel="noreferrer">termos de uso do Minecraft <ExternalLink size={12} /></a>.</span></label>
    <p className="small-note">A primeira inicialização pode levar alguns minutos. Quem tiver o endereço poderá entrar; o jogo pode ficar no ar enquanto o servidor e a hospedagem estiverem ligados.</p>
    <div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose} disabled={working}>Cancelar</button><button type="submit" className="button button-primary" disabled={!ready || working || !acceptEula}>{working ? <><LoaderCircle size={17} className="spin" /> Criando servidor...</> : <>Criar servidor <ArrowRight size={17} /></>}</button></div>
  </form>;
}

function Login({ onLogin, error, loading }) {
  const [value, setValue] = useState('');
  return <div className="login-page"><div className="login-card"><Brand /><div className="login-icon"><LockKeyhole size={23} /></div><span className="eyebrow">SEU ESPAÇO SEGURO</span><h1>Bem-vindo de volta.</h1><p>Digite a chave de acesso configurada na sua hospedagem para abrir seu painel.</p><form onSubmit={(event) => { event.preventDefault(); onLogin(value); }}><label className="form-field"><span>Chave de acesso</span><input type="password" value={value} onChange={(event) => setValue(event.target.value)} placeholder="Sua chave de acesso" autoComplete="current-password" required /></label>{error && <p className="login-error" role="alert">{error}</p>}<button className="button button-primary" disabled={loading || !value}>{loading ? <LoaderCircle size={17} className="spin" /> : <>Entrar no painel <ArrowRight size={17} /></>}</button></form><small>A chave é definida por quem instalou o painel.</small></div><p className="login-foot">Um mundo inteiro esperando por você. ✳</p></div>;
}

export default function App() {
  const [status, setStatus] = useState(null);
  const [health, setHealth] = useState(null);
  const [token, setToken] = useState(() => sessionStorage.getItem('bloco-token') || '');
  const [locked, setLocked] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [loadingLogin, setLoadingLogin] = useState(false);
  const [connectionError, setConnectionError] = useState(false);
  const [active, setActive] = useState('inicio');
  const [modal, setModal] = useState(null);
  const [working, setWorking] = useState(false);
  const [toast, setToast] = useState(null);
  const [logs, setLogs] = useState('');
  const [installPrompt, setInstallPrompt] = useState(null);
  const worldInput = useRef(null);
  const packsInput = useRef(null);
  const toastTimer = useRef(null);
  const current = status || EMPTY;
  const canManage = current.panelConfigured && current.dockerAvailable && !current.hostError && !connectionError;

  const notify = useCallback((message, type = 'success') => {
    clearTimeout(toastTimer.current);
    setToast({ message, type });
    toastTimer.current = setTimeout(() => setToast(null), 5200);
  }, []);

  const request = useCallback(async (url, options = {}, credential = token) => {
    const headers = new Headers(options.headers || {});
    if (credential) headers.set('Authorization', `Bearer ${credential}`);
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    let response;
    try { response = await fetch(url, { ...options, headers }); }
    catch { throw new Error('Não foi possível conectar ao painel. Confira sua internet.'); }
    if (!response.ok) {
      let error = 'Não foi possível concluir a operação.';
      try { error = (await response.json()).error || error; } catch { /* generic message */ }
      if (response.status === 401 && health?.needsAuth) { sessionStorage.removeItem('bloco-token'); setLocked(true); }
      throw new Error(error);
    }
    return response;
  }, [token, health]);

  const refresh = useCallback(async (credential = token) => {
    try {
      const response = await request('/api/status', {}, credential);
      setStatus(await response.json());
      setConnectionError(false);
      return true;
    } catch (error) {
      if (!error.message.includes('Chave de acesso')) setConnectionError(true);
      return false;
    }
  }, [request, token]);

  useEffect(() => {
    let cancelled = false;
    fetch('/api/health').then((response) => {
      if (!response.ok) throw new Error('offline');
      return response.json();
    }).then(async (info) => {
      if (cancelled) return;
      setHealth(info);
      if (info.needsAuth && !token) { setLocked(true); return; }
      try {
        const headers = token ? { Authorization: `Bearer ${token}` } : {};
        const response = await fetch('/api/status', { headers });
        if (response.status === 401) { sessionStorage.removeItem('bloco-token'); if (!cancelled) setLocked(true); return; }
        if (!response.ok) throw new Error('offline');
        const data = await response.json();
        if (!cancelled) { setStatus(data); setConnectionError(false); setLocked(false); }
      } catch { if (!cancelled) setConnectionError(true); }
    }).catch(() => { if (!cancelled) { setHealth({ needsAuth: false, panelConfigured: false }); setConnectionError(true); } });
    return () => { cancelled = true; };
  }, [token]);

  useEffect(() => {
    if (locked || !health) return undefined;
    const timer = setInterval(() => refresh(), 12_000);
    return () => clearInterval(timer);
  }, [health, locked, refresh]);

  useEffect(() => {
    const listener = (event) => { event.preventDefault(); setInstallPrompt(event); };
    window.addEventListener('beforeinstallprompt', listener);
    return () => window.removeEventListener('beforeinstallprompt', listener);
  }, []);

  useEffect(() => () => clearTimeout(toastTimer.current), []);

  const install = async () => {
    if (!installPrompt) return;
    await installPrompt.prompt();
    await installPrompt.userChoice;
    setInstallPrompt(null);
  };

  const login = async (value) => {
    setLoadingLogin(true); setLoginError('');
    try {
      const response = await fetch('/api/status', { headers: { Authorization: `Bearer ${value}` } });
      if (!response.ok) throw new Error(response.status === 401 ? 'Chave de acesso incorreta. Tente novamente.' : 'Não foi possível conectar ao painel.');
      setStatus(await response.json());
      sessionStorage.setItem('bloco-token', value);
      setToken(value); setLocked(false); setConnectionError(false);
    } catch (error) { setLoginError(error.message || 'Não foi possível conectar ao painel.'); }
    finally { setLoadingLogin(false); }
  };

  const navigate = (id) => {
    setActive(id);
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  const openHelp = () => setModal({ type: 'help' });
  const goManage = () => current.server.exists ? navigate('servidor') : setModal({ type: 'create' });
  const run = async (operation, success, close = true) => {
    setWorking(true);
    try {
      const result = await operation();
      // Refresh the real server state before telling the user the operation finished.
      // Otherwise a quick next tap can still see the previous running/stopped state.
      await refresh();
      if (close) setModal(null);
      if (success) notify(typeof success === 'function' ? success(result) : success);
      return result;
    } catch (error) {
      notify(error.message, 'error');
      await refresh();
      return null;
    } finally { setWorking(false); }
  };
  const createServer = (data) => run(() => request('/api/server', { method: 'POST', body: JSON.stringify(data) }).then((r) => r.json()), 'Servidor criado! Ele está iniciando.');
  const serverAction = (action, success) => run(() => request('/api/server/action', { method: 'POST', body: JSON.stringify({ action }) }).then((r) => r.json()), success);
  const toggleServer = () => {
    if (!current.server.exists) { setModal({ type: 'create' }); return; }
    if (!canManage) { openHelp(); return; }
    if (current.server.running) setModal({ type: 'stop' });
    else serverAction('start', 'Seu servidor está iniciando.');
  };
  const prepareUpload = (kind) => {
    if (!canManage) { openHelp(); return; }
    if (current.server.running) { setModal({ type: 'needsStop', kind }); return; }
    if (kind === 'packs' && !current.world.exists) { setModal({ type: 'needsWorld' }); return; }
    (kind === 'world' ? worldInput : packsInput).current?.click();
  };
  const fileSelected = (event, kind) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    const allowed = kind === 'world' ? /\.(mcworld|zip)$/i : /\.(mcpack|mcaddon|zip)$/i;
    if (!allowed.test(file.name)) { notify('Escolha um arquivo em um dos formatos indicados.', 'error'); return; }
    if (file.size > 512 * 1024 * 1024) { notify('O arquivo deve ter no máximo 512 MB.', 'error'); return; }
    setModal({ type: 'upload', kind, file });
  };
  const upload = (kind, file) => {
    const body = new FormData(); body.append('file', file);
    run(() => request(kind === 'world' ? '/api/world' : '/api/packs', { method: 'POST', body }).then((r) => r.json()), (result) => kind === 'world' ? (result.backupCreated ? 'Mundo importado! O anterior foi guardado na hospedagem.' : 'Mundo importado! Inicie o servidor para jogar.') : `${result.added.length} conteúdo(s) adicionado(s). Inicie o servidor para aplicar.`);
  };
  const downloadWorld = () => {
    if (!canManage) { openHelp(); return; }
    if (current.server.running) { setModal({ type: 'needsStop', kind: 'download' }); return; }
    // Android uses the system file picker and streams the authenticated download to it.
    if (window.BlocoAndroid?.saveWorld) {
      try {
        window.BlocoAndroid.saveWorld(token);
        notify('Escolha onde salvar seu mundo.');
      } catch { notify('Não foi possível abrir o salvamento no celular.', 'error'); }
      return;
    }
    run(async () => {
      const response = await request('/api/world/download');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a'); link.href = url; link.download = 'mundo-bloco.mcworld'; document.body.appendChild(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    }, 'Download do mundo iniciado.', false);
  };
  const removePack = (pack) => run(() => request(`/api/packs/${pack.kind}/${pack.id}`, { method: 'DELETE' }).then((r) => r.json()), 'Conteúdo desativado neste mundo.');
  const viewLogs = async () => {
    setWorking(true);
    try { const response = await request('/api/server/logs'); setLogs((await response.json()).logs || 'Ainda não há registros.'); setModal({ type: 'logs' }); }
    catch (error) { notify(error.message, 'error'); }
    finally { setWorking(false); }
  };
  const copyAddress = async () => {
    if (!current.server.address) return;
    try {
      const value = `${current.server.address}:${current.server.port}`;
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value);
      else { const input = document.createElement('textarea'); input.value = value; document.body.appendChild(input); input.select(); if (!document.execCommand('copy')) throw new Error(); input.remove(); }
      notify('Endereço copiado para compartilhar!');
    } catch { notify('Não foi possível copiar. Selecione o endereço manualmente.', 'error'); }
  };

  if (locked) return <Login onLogin={login} error={loginError} loading={loadingLogin} />;

  const state = current.server.exists ? current.server.state : 'not_created';
  const hostWarning = connectionError ? 'Não foi possível conectar ao painel' : current.hostError ? 'A hospedagem precisa de atenção' : !current.dockerAvailable ? 'Hospedagem ainda não conectada' : !current.panelConfigured ? 'Proteja seu painel para começar' : null;
  const hostDescription = connectionError ? 'Verifique sua conexão ou se o painel está ligado na hospedagem.' : current.hostError || (!current.dockerAvailable ? 'Para jogar 24h, conecte o painel a uma hospedagem sempre ligada.' : 'Configure uma chave de acesso para habilitar as alterações.');

  return <div className="app-shell">
    <SideNav active={active} navigate={navigate} openHelp={openHelp} installPrompt={installPrompt} install={install} />
    <main className="main-area" id="inicio">
      <header className="topbar">
        <div className="desktop-breadcrumb"><span>SEU ESPAÇO</span><ChevronRight size={14} /><strong>VISÃO GERAL</strong></div>
        <div className="mobile-brand"><Brand compact /></div>
        <div className="topbar-actions"><span className="edition-badge"><span className="edition-cube">◆</span> BEDROCK EDITION</span><span className="top-divider" />{health?.needsAuth && <button className="top-icon logout" title="Sair" aria-label="Sair" onClick={() => { sessionStorage.removeItem('bloco-token'); setToken(''); setLocked(true); }}><LogOut size={18} /></button>}<button className="top-help" onClick={openHelp} aria-label="Precisa de ajuda?"><CircleHelp size={18} /><span>Precisa de ajuda?</span></button></div>
      </header>
      <div className="page-content">
        <div className="welcome-row"><div><div className="welcome-kicker"><span className="kicker-line" /> SEU PAINEL DE MINECRAFT</div><h1>Bom te ver por aqui<span className="hello-star">✳</span></h1><p>Tudo para sua próxima aventura, em um só lugar.</p></div><button className="welcome-help" onClick={openHelp}><LifeBuoy size={17} /> Como funciona <ArrowUpRight size={15} /></button></div>

        <section className="hero" aria-labelledby="hero-title">
          <div className="hero-content"><div className="hero-eyebrow"><span className="hero-eyebrow-dot" /> SEU MUNDO COMEÇA AQUI</div><h2 id="hero-title">Um mundo só seu.<br /><span>Uma galera inteira.</span></h2><p>Crie seu servidor Minecraft Bedrock e reúna seus amigos para viver novas aventuras.</p><button className="button hero-button" onClick={goManage}>{current.server.exists ? 'Gerenciar meu servidor' : 'Criar meu servidor'} <ArrowRight size={18} /></button><div className="hero-meta"><span><UsersRound size={15} /> Até 10 jogadores</span><i /> <span><Sparkles size={15} /> Versão estável recente</span></div></div>
          <div className="hero-stamp"><span>✳</span> FEITO PARA<br />JOGAR JUNTO</div>
        </section>

        {hostWarning && <div className="host-banner" role="status"><div className="host-banner-icon">{connectionError || !current.dockerAvailable ? <CloudOff size={19} /> : <ShieldCheck size={19} />}</div><div className="host-banner-copy"><strong>{hostWarning}</strong><span>{hostDescription}</span></div><button onClick={openHelp}>Como configurar <ArrowRight size={15} /></button></div>}

        <section className="section server-section" id="servidor">
          <div className="section-heading"><div><div className="section-overline">SEU CANTINHO</div><h2>Seu servidor <span className="heading-spark">✳</span></h2><p>Seu ponto de encontro está aqui.</p></div><button className="text-link" onClick={() => current.server.exists ? setModal({ type: 'server' }) : setModal({ type: 'create' })}>{current.server.exists ? 'Ver detalhes' : 'Configurar servidor'} <ArrowUpRight size={15} /></button></div>
          <div className="server-card">
            <div className="server-identity"><div className="server-thumb"><span className="thumb-indicator"><Gamepad2 size={12} /></span></div><div className="server-identity-copy"><div className="server-name-line"><h3>{current.server.exists ? current.server.name : 'Meu servidor'}</h3><span className="bedrock-tag">BEDROCK</span></div><StatusPill state={state} /><span className="server-hint">{current.server.exists ? 'Seu servidor de Minecraft' : 'Pronto para começar'}</span></div></div>
            <div className="server-metrics"><div className="metric address-metric"><span>ENDEREÇO</span><div><strong>{current.server.address || 'Ainda não disponível'}</strong>{current.server.address && <button className="copy-button" onClick={copyAddress} aria-label="Copiar endereço e porta" title="Copiar endereço e porta"><Copy size={15} /></button>}</div><small>{current.server.address ? `Porta ${current.server.port}` : 'Depois de criar o servidor'}</small></div><div className="metric"><span>JOGADORES</span><strong>{current.server.players ?? '—'} <em>/ {current.server.maxPlayers}</em></strong><small>lugares disponíveis</small></div><div className="metric version-metric"><span>VERSÃO</span><strong>{current.server.version || 'Mais recente'}</strong><small>{current.server.version ? 'versão em execução' : 'verificada ao iniciar'}</small></div></div>
            <div className="server-actions">{current.server.exists && <button className="server-more" onClick={() => setModal({ type: 'server' })} aria-label="Mais opções do servidor"><MoreHorizontal size={20} /></button>}<button className={`server-power ${current.server.running ? 'is-running' : ''}`} onClick={toggleServer} disabled={working} aria-label={current.server.exists ? (current.server.running ? 'Parar servidor' : 'Iniciar servidor') : 'Configurar servidor'} title={current.server.exists ? (current.server.running ? 'Parar servidor' : 'Iniciar servidor') : 'Configurar servidor'}>{working ? <LoaderCircle size={19} className="spin" /> : current.server.exists ? <Power size={19} /> : <ArrowRight size={19} />}</button></div>
          </div>
        </section>

        <section className="section personalize-section" aria-labelledby="personalize-title">
          <div className="section-heading"><div><div className="section-overline">DEIXE TUDO COM A SUA CARA</div><h2 id="personalize-title">O mundo é seu <span className="heading-spark">✳</span></h2><p>Personalize sua experiência do seu jeito.</p></div><span className="section-aside">SIMPLES ASSIM <span>↗</span></span></div>
          <div className="feature-grid">
            <article className="feature-card world-card" id="mundo"><div className="feature-top"><div className="feature-icon world-icon"><Map size={24} strokeWidth={1.7} /><span>✦</span></div><span className="feature-number">01 / MUNDO</span></div><h3>Traga seu mundo</h3><p>Já tem um mundo favorito? Continue sua aventura de onde parou.</p><div className="feature-actions"><button className="feature-link" onClick={() => prepareUpload('world')}>Importar mundo <ArrowRight size={16} /></button><span>.mcworld <b>·</b> .zip</span></div>{current.world.exists && <div className="feature-status"><CheckCircle2 size={14} /> {current.world.name || 'Mundo disponível'} <button onClick={downloadWorld}><ArrowDownToLine size={14} /> Baixar</button></div>}</article>
            <article className="feature-card addons-card" id="conteudos"><div className="feature-top"><div className="feature-icon addons-icon"><Puzzle size={24} strokeWidth={1.7} /><span>✦</span></div><span className="feature-number">02 / CONTEÚDO</span></div><h3>Add-ons e texturas</h3><p>Mais possibilidades para deixar o jogo com a sua personalidade.</p><div className="feature-actions"><button className="feature-link" onClick={() => prepareUpload('packs')}>Adicionar conteúdo <ArrowRight size={16} /></button><span>.mcpack <b>·</b> .mcaddon</span></div>{current.packs.length > 0 && <div className="feature-status"><CheckCircle2 size={14} /> {current.packs.length} conteúdo(s) ativo(s) <button onClick={() => setModal({ type: 'packs' })}>Gerenciar <ArrowRight size={13} /></button></div>}</article>
          </div>
        </section>

        <section className="getting-started" aria-labelledby="steps-title"><div className="steps-intro"><div className="steps-icon"><Sparkles size={19} /></div><div><div className="section-overline">PARTIU JOGAR?</div><h2 id="steps-title">É mais simples do que parece.</h2><p>Seu próximo mundo em três passos.</p></div></div><div className="steps-list"><div><span>01</span><p><strong>Crie seu servidor</strong>Escolha um nome e configure.</p></div><div><span>02</span><p><strong>Deixe com a sua cara</strong>Importe mundo e conteúdos.</p></div><div><span>03</span><p><strong>Chame a galera</strong>Compartilhe o IP e jogue!</p></div></div></section>
        <footer className="page-footer"><span>Feito para quem gosta de construir junto. <span className="footer-star">✳</span></span><span>Bloco não é afiliado à Mojang ou Microsoft.</span></footer>
      </div>
    </main>
    <input ref={worldInput} type="file" accept=".mcworld,.zip" className="visually-hidden" aria-label="Escolher arquivo de mundo" onChange={(event) => fileSelected(event, 'world')} />
    <input ref={packsInput} type="file" accept=".mcpack,.mcaddon,.zip" className="visually-hidden" aria-label="Escolher arquivo de conteúdo" onChange={(event) => fileSelected(event, 'packs')} />
    {toast && <div className={`toast ${toast.type}`} role="status">{toast.type === 'error' ? <AlertCircle size={18} /> : <CheckCircle2 size={18} />}<span>{toast.message}</span><button onClick={() => setToast(null)} aria-label="Fechar aviso"><X size={15} /></button></div>}

    {modal?.type === 'help' && <Modal title="Como o Bloco funciona" subtitle="Tudo que você precisa saber antes de começar." onClose={() => setModal(null)} wide><HelpContent onClose={() => setModal(null)} hostAvailable={current.dockerAvailable} panelConfigured={current.panelConfigured} installPrompt={installPrompt} install={install} /></Modal>}
    {modal?.type === 'create' && <Modal title="Vamos criar seu servidor?" subtitle="Só alguns detalhes e seu mundo fica pronto para começar." onClose={() => setModal(null)} busy={working}><CreateForm status={current} working={working} onSubmit={createServer} onHelp={openHelp} onClose={() => setModal(null)} /></Modal>}
    {modal?.type === 'stop' && <Modal title="Parar o servidor?" subtitle="Todo mundo será desconectado, mas o mundo será salvo." onClose={() => setModal(null)} busy={working}><div className="modal-illustration"><Power size={22} /></div><p className="modal-copy">Você pode iniciar seu servidor novamente quando quiser. Enquanto ele estiver parado, ninguém poderá entrar.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal(null)} disabled={working}>Continuar jogando</button><button className="button button-danger" onClick={() => serverAction('stop', 'Servidor parado com segurança.')} disabled={working}>{working ? <LoaderCircle size={17} className="spin" /> : <Power size={16} />} Parar servidor</button></div></Modal>}
    {modal?.type === 'needsStop' && <Modal title="Primeiro, pare o servidor" subtitle="Precisamos proteger seu mundo enquanto mexemos nos arquivos." onClose={() => setModal(null)} busy={working}><div className="modal-illustration amber"><HardDrive size={22} /></div><p className="modal-copy">Para {modal.kind === 'world' ? 'importar um mundo' : modal.kind === 'download' ? 'baixar seu mundo' : 'alterar os conteúdos'}, pare o servidor e aguarde o salvamento. Depois, volte aqui e escolha o arquivo.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal(null)} disabled={working}>Agora não</button><button className="button button-primary" onClick={() => serverAction('stop', 'Servidor parado. Agora você pode continuar.')} disabled={working}>{working ? <LoaderCircle size={17} className="spin" /> : <Power size={16} />} Parar servidor</button></div></Modal>}
    {modal?.type === 'needsWorld' && <Modal title="Antes, crie seu mundo" subtitle="Os conteúdos precisam de um mundo para funcionar." onClose={() => setModal(null)}><div className="modal-illustration"><Globe2 size={22} /></div><p className="modal-copy">Crie e inicie seu servidor uma vez para gerar um mundo, ou importe um arquivo .mcworld. Depois, pare o servidor e adicione seus conteúdos.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal(null)}>Entendi</button><button className="button button-primary" onClick={() => { setModal(null); if (!current.server.exists) setModal({ type: 'create' }); else navigate('servidor'); }}>Ir para o servidor <ArrowRight size={16} /></button></div></Modal>}
    {modal?.type === 'upload' && <Modal title={modal.kind === 'world' ? 'Importar este mundo?' : 'Adicionar este conteúdo?'} subtitle={modal.kind === 'world' ? 'Sua próxima aventura está quase pronta.' : 'Vamos deixar seu mundo ainda mais divertido.'} onClose={() => setModal(null)} busy={working}><div className="selected-file"><div><FileArchive size={22} /></div><span><strong>{modal.file.name}</strong><small>{(modal.file.size / (1024 * 1024)).toFixed(2)} MB · arquivo selecionado</small></span><CheckCircle2 size={19} /></div><div className="upload-note"><AlertCircle size={17} /><p>{modal.kind === 'world' ? 'O mundo atual será substituído. Uma cópia dele ficará salva na hospedagem. Mundos protegidos pelo Marketplace podem não ser transferíveis.' : 'Use conteúdos compatíveis com a versão Bedrock. Alguns add-ons podem exigir recursos experimentais ou licenças próprias.'}</p></div>{working && <p className="small-note">O envio pode demorar. Mantenha o aplicativo aberto até a importação terminar.</p>}<div className="modal-actions"><button className="button button-secondary" onClick={() => setModal(null)} disabled={working}>Cancelar</button><button className="button button-primary" onClick={() => upload(modal.kind, modal.file)} disabled={working}>{working ? <><LoaderCircle size={17} className="spin" /> Importando...</> : <><Upload size={16} /> {modal.kind === 'world' ? 'Importar mundo' : 'Adicionar conteúdo'}</>}</button></div></Modal>}
    {modal?.type === 'server' && <Modal title="Detalhes do servidor" subtitle="As informações para reunir sua galera." onClose={() => setModal(null)}><div className="detail-title"><div className="detail-cube"><Server size={21} /></div><div><strong>{current.server.name}</strong><StatusPill state={state} /></div></div><div className="detail-list"><div><span>Endereço</span><strong>{current.server.address || 'Indisponível'} {current.server.address && <button onClick={copyAddress} aria-label="Copiar endereço"><Copy size={15} /></button>}</strong></div><div><span>Porta</span><strong>{current.server.port}</strong></div><div><span>Modo de jogo</span><strong>{MODE[current.server.mode] || current.server.mode}</strong></div><div><span>Dificuldade</span><strong>{DIFFICULTY[current.server.difficulty] || current.server.difficulty}</strong></div><div><span>Jogadores</span><strong>Até {current.server.maxPlayers}</strong></div><div><span>Versão</span><strong>{current.server.version || 'Mais recente ao iniciar'}</strong></div></div><p className="small-note">Para entrar: no Minecraft Bedrock, vá em Servidores → Adicionar servidor e informe o endereço e a porta.</p><div className="detail-actions"><button onClick={() => setModal({ type: 'update' })} disabled={!canManage || working}><RefreshCw size={16} /> Verificar atualização</button><button onClick={viewLogs} disabled={!canManage || working}><TerminalSquare size={16} /> Ver registros</button></div><div className="modal-actions"><button className="button button-primary" onClick={() => setModal(null)}>Tudo certo <Check size={16} /></button></div></Modal>}
    {modal?.type === 'update' && <Modal title="Verificar versão mais recente?" subtitle="O servidor será iniciado novamente para verificar atualizações." onClose={() => setModal({ type: 'server' })} busy={working}><div className="modal-illustration"><RefreshCw size={22} /></div><p className="modal-copy">Se estiver ligado, o servidor será reiniciado e os jogadores serão desconectados. O mundo será salvo antes de parar.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal({ type: 'server' })} disabled={working}>Cancelar</button><button className="button button-primary" onClick={() => serverAction('restart', 'Verificando a versão mais recente ao iniciar.')} disabled={working}>{working ? <LoaderCircle size={17} className="spin" /> : <RefreshCw size={16} />} Continuar</button></div></Modal>}
    {modal?.type === 'logs' && <Modal title="Registros do servidor" subtitle="As últimas mensagens enviadas pelo servidor Bedrock." onClose={() => setModal({ type: 'server' })} wide><pre className="logs">{logs}</pre><div className="modal-actions"><button className="button button-primary" onClick={() => setModal({ type: 'server' })}>Voltar</button></div></Modal>}
    {modal?.type === 'packs' && <Modal title="Seus conteúdos" subtitle="Add-ons e texturas ativos neste mundo." onClose={() => setModal(null)}><div className="pack-list">{current.packs.map((pack) => <div className="pack-item" key={`${pack.kind}-${pack.id}`}><div className="pack-icon"><Puzzle size={17} /></div><div><strong>{pack.name}</strong><span>{pack.kind === 'behavior' ? 'Add-on' : 'Textura'} · {Array.isArray(pack.version) ? pack.version.join('.') : 'versão desconhecida'}</span></div><button title="Desativar conteúdo" aria-label={`Desativar ${pack.name}`} onClick={() => current.server.running ? setModal({ type: 'needsStop', kind: 'packs' }) : setModal({ type: 'removePack', pack })}><Trash2 size={17} /></button></div>)}</div><p className="small-note">Para alterar conteúdos, o servidor precisa estar parado.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal(null)}>Fechar</button><button className="button button-primary" onClick={() => { setModal(null); prepareUpload('packs'); }}><PackagePlus size={16} /> Adicionar</button></div></Modal>}
    {modal?.type === 'removePack' && <Modal title="Desativar conteúdo?" subtitle="Você pode adicioná-lo novamente quando quiser." onClose={() => setModal({ type: 'packs' })} busy={working}><p className="modal-copy">O conteúdo <strong>{modal.pack.name}</strong> deixará de ficar ativo neste mundo na próxima inicialização. Os arquivos serão mantidos na hospedagem.</p><div className="modal-actions"><button className="button button-secondary" onClick={() => setModal({ type: 'packs' })} disabled={working}>Cancelar</button><button className="button button-danger" onClick={() => removePack(modal.pack)} disabled={working}>{working ? <LoaderCircle size={17} className="spin" /> : <Trash2 size={16} />} Desativar</button></div></Modal>}
  </div>;
}
