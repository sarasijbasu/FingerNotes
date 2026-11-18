import XLSX from 'xlsx-js-style';
import { PAYMENT_LABELS, type Category, type Expense } from './model';

/** docs/PROTOCOL.md §9 — mirrored column-for-column by the Android FastExcel exporter. */
export const XLSX_HEADERS = ['Date', 'Time', 'Title / Merchant', 'Category', 'Payment Method', 'Currency', 'Amount', 'Notes'];

const EXCEL_EPOCH = Date.UTC(1899, 11, 30);
/** Local calendar date → Excel serial (no time-zone drift). */
export function excelDate(ms: number): number {
  const d = new Date(ms);
  return (Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) - EXCEL_EPOCH) / 86_400_000;
}
export function excelTime(ms: number): number {
  const d = new Date(ms);
  return (d.getHours() * 3600 + d.getMinutes() * 60) / 86_400;
}

export function filterByRange(expenses: Expense[], from?: number, to?: number) {
  return expenses
    .filter((e) => !e.isTrashed && (from == null || e.occurredAt >= from) && (to == null || e.occurredAt <= to))
    .sort((a, b) => a.occurredAt - b.occurredAt);
}

export function buildExpenseWorkbook(expenses: Expense[], categories: Map<string, Category>): Uint8Array {
  const header = { font: { bold: true, color: { rgb: 'FFFFFF' } }, fill: { fgColor: { rgb: '37474F' } }, alignment: { horizontal: 'center' } };
  const bold = { font: { bold: true } };
  const rows: XLSX.CellObject[][] = [XLSX_HEADERS.map((h) => ({ t: 's', v: h, s: header }) as XLSX.CellObject)];
  for (const e of expenses) {
    rows.push([
      { t: 'n', v: excelDate(e.occurredAt), z: 'yyyy-mm-dd' },
      { t: 'n', v: excelTime(e.occurredAt), z: 'hh:mm' },
      { t: 's', v: e.title },
      { t: 's', v: categories.get(e.categoryId)?.name ?? 'Uncategorized' },
      { t: 's', v: PAYMENT_LABELS[e.paymentMethod] },
      { t: 's', v: e.currency },
      { t: 'n', v: e.amount, z: '#,##0.00' },
      { t: 's', v: e.notes },
    ] as XLSX.CellObject[]);
  }
  const last = expenses.length + 1;
  const total = expenses.reduce((s, e) => s + e.amount, 0);
  rows.push([
    ...Array.from({ length: 5 }, () => ({ t: 's', v: '' }) as XLSX.CellObject),
    { t: 's', v: 'TOTAL', s: bold },
    { t: 'n', v: Math.round(total * 100) / 100, f: `SUM(G2:G${last})`, z: '#,##0.00', s: bold },
    { t: 's', v: '' },
  ] as XLSX.CellObject[]);

  const ws = XLSX.utils.aoa_to_sheet(rows);
  ws['!cols'] = [12, 8, 28, 14, 16, 10, 14, 36].map((wch) => ({ wch }));
  ws['!freeze'] = { xSplit: 0, ySplit: 1 };
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Expenses');
  return new Uint8Array(XLSX.write(wb, { type: 'array', bookType: 'xlsx', compression: true }) as ArrayBuffer);
}
