/** Static UI strings only. Arguments are inserted once, not translated or interpreted as HTML. */
export function t(key, fallback, ...args) {
  const pattern=globalThis.DB_COMPANION_MESSAGES?.[key] ?? fallback;
  return pattern.replace(/\{(\d+)\}/g,(all,index)=>index<args.length?String(args[index]):all);
}
