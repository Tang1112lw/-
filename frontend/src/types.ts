export interface CommentPlan {
  scene?: string;
  text?: string;
}

export interface Comment {
  seq: number;
  id?: string;
  nickname?: string;
  text: string;
  rawText?: string;
  system?: boolean;
  at: string;
}

export interface SessionStatus {
  id: string;
  name: string;
  state: string;
  phase: string;
  roomUrl: string;
  cdpUrl: string;
  loggedIn: boolean;
  autoEnabled: boolean;
  browserTitle: string;
  currentUrl: string;
  commentCount: number;
  sentActions: Record<string, number>;
  lastComment?: Comment;
  recentComments: Comment[];
  lastDecision?: CommentPlan;
  pendingDecision?: CommentPlan;
  pendingCommentAt?: string;
  pendingIntervalSeconds?: number;
  lastAction?: string;
  lastError?: string;
  startedAt?: string;
  nextRunAt?: string;
  logs: string[];
  policy: {
    autoEnabled?: boolean;
    [key: string]: unknown;
  };
}

export interface LoginSyncStatus {
  imported: boolean;
  importedAt?: string;
  sourceProfile?: string;
  dailyEdgeRunning: boolean;
  cookiePrunePending?: boolean;
  message?: string;
  hint?: string;
}

export interface AppStatus {
  corpusSize: number;
  corpusThreads?: number;
  corpusScenes?: Record<string, number>;
  roomUrl: string;
  browserMode: string;
  cdpUrl: string;
  autoActions: boolean;
  maxSessions: number;
  loopMinIntervalMs: number;
  loopMaxIntervalMs: number;
  loginSync?: LoginSyncStatus;
  advice: string;
}