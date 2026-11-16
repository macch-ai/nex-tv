/**
 * PIN del acceso técnico de NEX TV (schema-mdm-v18.sql). El panel nunca manda el PIN: lo convierte acá en un hash
 * PBKDF2-SHA256 con sal y el servidor solo guarda ese hash. El equipo lo recibe en el sync y lo verifica (TechPin.kt).
 */
export interface TechPinHash {
  v: 1;
  salt: string;
  iter: number;
  hash: string;
}

export const TECH_PIN_ITERATIONS = 20_000;

/** 4 dígitos (la pantalla de la TV tiene 4 casillas). Rechaza los obvios: todos iguales o 1234 / 4321. */
export function techPinProblem(pin: string): string | null {
  if (!/^[0-9]{4}$/.test(pin)) return 'El PIN tiene que tener 4 números.';
  if (/^(\d)\1{3}$/.test(pin) || ['1234', '4321', '0123', '9876'].includes(pin)) return 'Elegí un PIN menos obvio.';
  return null;
}

const toHex = (b: ArrayBuffer | Uint8Array) =>
  Array.from(b instanceof Uint8Array ? b : new Uint8Array(b), (x) => x.toString(16).padStart(2, '0')).join('');

export async function hashTechPin(pin: string, salt: Uint8Array = crypto.getRandomValues(new Uint8Array(16)),
  iter = TECH_PIN_ITERATIONS): Promise<TechPinHash> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: new Uint8Array(salt), iterations: iter }, key, 256);
  return { v: 1, salt: toHex(salt), iter, hash: toHex(bits) };
}
