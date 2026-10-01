const groupDigits = (int: string) => int.replace(/\B(?=(\d{3})+(?!\d))/g, '.');

export function formatBRL(value: number) {
  const [int, dec] = Math.abs(value).toFixed(2).split('.');
  return `${value < 0 ? '-' : ''}R$ ${groupDigits(int)},${dec}`;
}

export function formatNumber(value: number, digits = 2) {
  const [int, dec] = Math.abs(value).toFixed(digits).split('.');
  return `${value < 0 ? '-' : ''}${groupDigits(int)}${dec ? `,${dec}` : ''}`;
}

export function formatInt(value: number) {
  return formatNumber(value, 0);
}
