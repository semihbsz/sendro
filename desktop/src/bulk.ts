/**
 * Bounded-concurrency bulk helper.
 *
 * PROTOCOL.md §12: there is no batch endpoint anywhere in Sendro — every
 * "…all" affordance is a client-side loop over the single-item call, run at
 * most 4 in flight, reporting per-item failures without aborting the rest.
 */
export const BULK_CONCURRENCY = 4;

export interface BulkFailure<T> {
  item: T;
  error: unknown;
}

/**
 * Run `fn` over `items` with at most `limit` in flight. Never rejects:
 * resolves with the items that failed, in completion order.
 */
export async function mapLimited<T>(
  items: readonly T[],
  limit: number,
  fn: (item: T) => Promise<unknown>,
): Promise<BulkFailure<T>[]> {
  const failures: BulkFailure<T>[] = [];
  let next = 0;
  const width = Math.max(1, Math.min(limit, items.length));

  const worker = async () => {
    for (;;) {
      const index = next++;
      if (index >= items.length) return;
      const item = items[index] as T;
      try {
        await fn(item);
      } catch (error) {
        failures.push({ item, error });
      }
    }
  };

  await Promise.all(Array.from({ length: width }, worker));
  return failures;
}
