export interface AdminUser {
  id: number;
  username: string;
  displayName: string | null;
  role: 'ADMIN' | 'USER';
  createdAt: string;
  books: string[];
}

/** One runtime feature flag (E-6). `overridden` = a DB row wins over the env default. */
export interface FeatureFlag {
  name: string;
  description: string;
  enabled: boolean;
  envDefault: boolean;
  overridden: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}
