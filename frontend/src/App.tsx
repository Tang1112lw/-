import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { AppStatus, Comment, SessionStatus } from './types';

function formatTime(value?: string) {
  if (!value) return '—';
  try {
    return new Date(value).toLocaleTimeString('zh-CN', { hour12: false });
  } catch {
    return '—';
  }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function formatDateTime(value?: string) {
  if (!value) return '';
  try {
    return new Date(value).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });
  } catch {
    return '';
  }
}

function StatusItem({
  label,
  value,
  className = '',
}: {
  label: string;
  value: string | number;
  className?: string;
}) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <b className={className}>{value}</b>
    </div>
  );
}

export default function App() {
  const [status, setStatus] = useState<AppStatus | null>(null);
  const [sessions, setSessions] = useState<SessionStatus[]>([]);
  const [connected, setConnected] = useState(true);
  const [toast, setToast] = useState('');
  const [now, setNow] = useState(Date.now());

  const [minSec, setMinSec] = useState('15');
  const [maxSec, setMaxSec] = useState('60');
  const [intervalDirty, setIntervalDirty] = useState(false);
  const [roomUrl, setRoomUrl] = useState('');
  const [roomDirty, setRoomDirty] = useState(false);
  const [roomBusy, setRoomBusy] = useState(false);

  const session = sessions[0];

  const showToast = useCallback((message: string) => {
    setToast(message);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 3200);
    return () => clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const [nextStatus, nextSessions] = await Promise.all([api.status(), api.sessions()]);
      setStatus(nextStatus);
      setSessions(nextSessions);
      setConnected(true);
      if (!intervalDirty) {
        setMinSec(String(Math.round(nextStatus.loopMinIntervalMs / 1000)));
        setMaxSec(String(Math.round(nextStatus.loopMaxIntervalMs / 1000)));
      }
      if (!roomDirty) {
        setRoomUrl(nextSessions[0]?.roomUrl || nextStatus.roomUrl || '');
      }
    } catch {
      setConnected(false);
    }
  }, [intervalDirty, roomDirty]);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 1000);
    return () => clearInterval(timer);
  }, [refresh]);

  const comments = useMemo(
    () => (session?.recentComments ?? []).slice(-30).reverse(),
    [session?.recentComments],
  );

  const pending = session?.pendingDecision;
  const auto = Boolean(session?.policy?.autoEnabled ?? status?.autoActions);
  const sentComments = session?.sentActions?.COMMENT ?? 0;
  const sync = status?.loginSync;
  const syncText = sync
    ? sync.imported
      ? `已同步 ${formatDateTime(sync.importedAt)}`.trim()
      : sync.dailyEdgeRunning
        ? '待同步（Edge 运行中）'
        : '未同步'
    : '—';
  const stateClass =
    session?.state === 'RUNNING'
      ? 'ok'
      : session?.state === 'ERROR'
        ? 'bad'
        : session?.state === 'STARTING'
          ? 'warn'
          : '';

  const countdown =
    pending && session?.pendingCommentAt
      ? Math.max(0, Math.ceil((new Date(session.pendingCommentAt).getTime() - now) / 1000))
      : null;

  const nextText = !session
    ? '—'
    : session.phase === 'GENERATING'
      ? '生成中…'
      : session.phase === 'ACTING'
        ? '执行中…'
        : session.phase === 'CONNECTING'
          ? '连接中…'
          : session.phase === 'ENDED'
            ? '直播已结束'
            : session.state === 'STOPPED' || session.state === 'CREATED'
              ? '—'
              : session.nextRunAt
                ? `${Math.max(0, Math.ceil((new Date(session.nextRunAt).getTime() - now) / 1000))} 秒`
                : '—';

  const planMode =
    session?.phase === 'GENERATING'
      ? '生成中…'
      : session?.phase === 'ACTING'
        ? '执行中…'
        : auto
          ? '自动发送'
          : '仅建议';

  const planContent =
    session?.phase === 'GENERATING'
      ? '正在从语料库挑选…'
      : session?.phase === 'ACTING'
        ? `正在执行：${pending?.text || '发送评论'}`
        : pending
          ? pending.text || '—'
          : session?.state === 'STOPPED'
            ? '会话已停止'
            : '等待下一轮随机取词…';

  const start = async () => {
    try {
      const result = session ? await api.start(session.id) : await api.startAll();
      showToast(result.message);
      await refresh();
    } catch (error) {
      showToast(errorMessage(error));
    }
  };

  const stop = async () => {
    try {
      const result = await api.stopAll();
      showToast(result.message);
      await refresh();
    } catch (error) {
      showToast(errorMessage(error));
    }
  };

  const openLogin = async () => {
    if (!session) return;
    try {
      const result = await api.login(session.id);
      showToast(result.message);
      await refresh();
    } catch (error) {
      showToast(errorMessage(error));
    }
  };

  const saveInterval = async () => {
    const min = Number(minSec);
    const max = Number(maxSec);
    if (!Number.isFinite(min) || !Number.isFinite(max) || min < 1 || max > 86400 || min > max) {
      showToast('请输入 1–86400 秒（1 秒到 24 小时），且最小值不大于最大值');
      return;
    }
    try {
      await api.saveInterval(min, max);
      setIntervalDirty(false);
      showToast(`已保存：${min}–${max} 秒`);
      await refresh();
    } catch (error) {
      showToast(errorMessage(error));
    }
  };

  const saveRoom = async () => {
    if (!session) {
      showToast('会话尚未创建');
      return;
    }
    if (!roomUrl.trim()) {
      showToast('请输入直播间地址');
      return;
    }
    setRoomBusy(true);
    try {
      const result = await api.updateRoom(session.id, roomUrl.trim());
      setRoomUrl(result.roomUrl || roomUrl.trim());
      setRoomDirty(false);
      showToast(result.message);
      await refresh();
    } catch (error) {
      showToast(errorMessage(error));
    } finally {
      setRoomBusy(false);
    }
  };

  return (
    <div className="app">
      <header>
        <div className="brand">
          <img className="brand-logo" src="/brand-logo.png" alt="品牌 Logo" />
          <h1>中控直播助手</h1>
        </div>
        <div className="header-right">
          <span className="corpus-hint">本地语料 {status?.corpusSize ?? 0} 条 · {status?.corpusThreads ?? 0} 条线程</span>
          <span className={`conn ${connected ? 'ok' : 'bad'}`}>
            {connected ? '已连接' : '后端未连接'}
          </span>
        </div>
      </header>

      <div className="control-bar">
        <button className="btn primary" onClick={start}>
          启动会话
        </button>
        <button className="btn danger" onClick={stop}>
          停止全部
        </button>
        <button className="btn ghost" onClick={refresh}>
          刷新状态
        </button>
        {session?.state === 'RUNNING' && !session.loggedIn && (
          <button className="btn ghost" onClick={openLogin}>
            打开登录窗口
          </button>
        )}
      </div>

      <div className="room-bar">
        <span>直播间地址</span>
        <input
          type="url"
          value={roomUrl}
          placeholder="https://live.douyin.com/..."
          onChange={(event) => {
            setRoomUrl(event.target.value);
            setRoomDirty(true);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              void saveRoom();
            }
          }}
        />
        <button className="btn primary" onClick={saveRoom} disabled={roomBusy}>
          {roomBusy ? '切换中…' : '应用地址'}
        </button>
      </div>

      <div className="status-strip">
        <StatusItem label="运行状态" value={session?.state ?? '—'} className={`state ${stateClass}`} />
        <StatusItem
          label="登录状态"
          value={session ? (session.loggedIn ? '已登录' : '未登录') : '—'}
          className={`state ${session?.loggedIn ? 'ok' : 'bad'}`}
        />
        <StatusItem
          label="自动模式"
          value={session ? (auto ? '开' : '关') : '—'}
          className={`state ${auto ? 'ok' : ''}`}
        />
        <StatusItem
          label="登录同步"
          value={syncText}
          className={`state ${sync?.imported ? 'ok' : ''}`}
        />
        <StatusItem label="收到评论" value={session?.commentCount ?? 0} />
        <StatusItem label="本次已发评论" value={sentComments} className="accent" />
        <StatusItem label="下一轮" value={nextText} />
      </div>

      {session?.state === 'RUNNING' && !session.loggedIn && (
        <div className="login-tip">
          登录请在弹出的「自动化浏览器」窗口扫码，不是当前这个控制台窗口。登录成功后这里会自动变成“已登录”。
          {sync && !sync.imported && sync.hint ? ` ${sync.hint}` : ''}
        </div>
      )}

      <div className="interval-bar">
        <span>评论间隔</span>
        <input
          type="number"
          min={1}
          max={86400}
          value={minSec}
          onChange={(event) => {
            setMinSec(event.target.value);
            setIntervalDirty(true);
          }}
        />
        <span>–</span>
        <input
          type="number"
          min={1}
          max={86400}
          value={maxSec}
          onChange={(event) => {
            setMaxSec(event.target.value);
            setIntervalDirty(true);
          }}
        />
        <span>秒</span>
        <button className="btn ghost" onClick={saveInterval}>
          保存
        </button>
      </div>

      <main className="content">
        <section className="panel">
          <div className="panel-head">
            <h2>实时评论</h2>
            <span>{session?.commentCount ?? 0} 条</span>
          </div>
          <div className="comment-list">
            {comments.length ? (
              comments.map((comment: Comment) => (
                <div className="comment-row" key={`${comment.seq}-${comment.at}`}>
                  <span className="comment-time">{formatTime(comment.at)}</span>
                  <span className="comment-nick">{comment.nickname || '观众'}</span>
                  <span className="comment-text">{comment.text}</span>
                </div>
              ))
            ) : (
              <div className="empty">等待评论…</div>
            )}
          </div>
        </section>

        <section className="panel">
          <div className="panel-head">
            <h2>下次评论</h2>
            <span>{planMode}</span>
          </div>
          <div className="plan">
            <div className="plan-grid">
              <div>
                <span>预计时间</span>
                <b>{pending ? formatTime(session?.pendingCommentAt) : '—'}</b>
              </div>
              <div>
                <span>倒计时</span>
                <b className="accent">{countdown !== null ? `${countdown} 秒` : '—'}</b>
              </div>
              <div>
                <span>本次间隔</span>
                <b>
                  {session?.pendingIntervalSeconds
                    ? `${session.pendingIntervalSeconds} 秒`
                    : '—'}
                </b>
              </div>
              <div>
                <span>场景</span>
                <b>{pending ? pending.scene || '—' : '—'}</b>
              </div>
            </div>
            <div className="plan-label">评论内容</div>
            <div className="plan-content">{planContent}</div>
            {session?.lastAction ? (
              <div className="plan-note">最近动作：{session.lastAction}</div>
            ) : null}
            {session?.lastError ? (
              <div className="plan-note bad">最近错误：{session.lastError}</div>
            ) : null}
          </div>
        </section>
      </main>

      <footer>
        <span>全程本地语料随机取词，不调用 AI；想复用日常 Edge 的抖音登录：先完全关闭 Edge，再启动本程序，程序会自动导入登录态。</span>
        <span>真实直播间操作有账号风险，请遵守平台规则。</span>
      </footer>

      {toast && <div className="toast show">{toast}</div>}
    </div>
  );
}