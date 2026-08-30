export interface PortalUser {
  id: number;
  username: string;
  displayName: string | null;
  role: 'ADMIN' | 'USER';
  books: string[];
  /** TOTP 2FA state (E-7) — only present from GET /api/auth/me. */
  mfaEnabled?: boolean;
  backupCodesLeft?: number;
}
