/** Reads editor-owned state; never copies input values or retains database contents. */
export function createEditGuard() {
  const readers = new Set();
  const hasChanges = () => [...readers].some(read => read());
  return {
    register(read) { readers.add(read); return () => readers.delete(read); },
    hasChanges,
    canLeave(confirmDiscard, otherChanges = false) {
      return !(otherChanges || hasChanges()) || confirmDiscard();
    },
  };
}
export const editGuard = createEditGuard();
