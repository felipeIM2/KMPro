let skipGate = false;

/** Sinaliza que o usuário optou por seguir sem os acessos (uso único). */
export function setSkipAccessGate(): void {
  skipGate = true;
}

/** Consome o sinal: retorna true (e limpa) se o gate deve ser ignorado. */
export function consumeSkipAccessGate(): boolean {
  if (!skipGate) return false;
  skipGate = false;
  return true;
}