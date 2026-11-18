export type Platform = 'android' | 'chrome';
export const SCHEMA_VERSION = 1;
export const APP_VERSION = '1.0.0';
export const DAY_MS = 86_400_000;
export const TRASH_RETENTION_DAYS = 30;
export const TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * DAY_MS;

export interface Versioned {
  id: string;
  createdAt: number;
  updatedAt: number;
  hlc: string;
}

export interface ChecklistItem {
  id: string;
  text: string;
  checked: boolean;
  position: number;
}

export interface Attachment {
  id: string;
  kind: 'image' | 'audio';
  mime: string;
  size: number;
  name: string;
  createdAt: number;
}

export type NoteColor = 'default' | 'red' | 'orange' | 'yellow' | 'green' | 'teal' | 'blue' | 'purple' | 'pink' | 'brown' | 'gray';
export const NOTE_COLORS: NoteColor[] = ['default', 'red', 'orange', 'yellow', 'green', 'teal', 'blue', 'purple', 'pink', 'brown', 'gray'];

export interface Note extends Versioned {
  kind: 'text' | 'checklist';
  title: string;
  body: string;
  items: ChecklistItem[];
  color: NoteColor;
  labelIds: string[];
  attachments: Attachment[];
  isPinned: boolean;
  isArchived: boolean;
  isHidden: boolean;
  isTrashed: boolean;
  deletedAt: number | null;
}

export interface Label extends Versioned {
  name: string;
}

export type PaymentMethod = 'CASH' | 'CARD' | 'UPI_BANK';
export const PAYMENT_LABELS: Record<PaymentMethod, string> = { CASH: 'Cash', CARD: 'Card', UPI_BANK: 'UPI/Bank' };

export interface Expense extends Versioned {
  amount: number;
  currency: string;
  title: string;
  categoryId: string;
  paymentMethod: PaymentMethod;
  occurredAt: number;
  notes: string;
  receipt: Attachment | null;
  isTrashed: boolean;
  deletedAt: number | null;
}

export interface Category extends Versioned {
  name: string;
  color: string;
  builtIn: boolean;
}

export type EntityType = 'note' | 'expense' | 'label' | 'category';
export interface Tombstone {
  id: string;
  entityType: EntityType;
  purgedAt: number;
  hlc: string;
}

export const BUILTIN_CATEGORIES: Array<Pick<Category, 'id' | 'name' | 'color'>> = [
  { id: 'food', name: 'Food', color: '#F4511E' },
  { id: 'transport', name: 'Transport', color: '#039BE5' },
  { id: 'bills', name: 'Bills', color: '#8E24AA' },
  { id: 'shopping', name: 'Shopping', color: '#43A047' },
  { id: 'health', name: 'Health', color: '#E53935' },
];

export function daysUntilPurge(deletedAt: number, now = Date.now()): number {
  return Math.max(0, Math.ceil((deletedAt + TRASH_RETENTION_MS - now) / DAY_MS));
}

export function emptyNote(id: string, now: number, hlc: string, kind: Note['kind'] = 'text'): Note {
  return {
    id, kind, title: '', body: '', items: [], color: 'default', labelIds: [], attachments: [],
    isPinned: false, isArchived: false, isHidden: false, isTrashed: false, deletedAt: null,
    createdAt: now, updatedAt: now, hlc,
  };
}
