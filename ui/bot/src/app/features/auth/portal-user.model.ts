export interface PortalUser {
  id: number;
  username: string;
  displayName: string | null;
  role: 'ADMIN' | 'USER';
  books: string[];
}
