package org.sagebionetworks.repo.manager.grid.synch.core;

/**
 * A one-directional output notified by {@link SynchronizationLogic} of the
 * terminal fate of each item that survives into the reconciled copy. It lets a
 * downstream consumer (e.g. a source that builds a new pushed artifact from the
 * final grid contents) capture surviving items.
 *
 * <p>
 * The listener never feeds the {@link Copy} or {@link Source}; the engine (and
 * the {@link Merge} for merged items) push into it. All methods default to a
 * no-op, so phases that build no derived artifact (schema and cell
 * synchronization) can ignore it entirely via {@link #noOp()}.
 *
 * @param <C> the type of items in the copy
 * @param <S> the type of items in the source
 */
public interface SyncOutcomeListener<C extends CopyItem, S extends SourceItem> {

	/**
	 * Notification that a copy item was retained unchanged during Phase 1.
	 *
	 * @param copyItem   the unchanged item from the copy
	 */
	default void onRetainedInCopy(C copyItem) {
		// no-op by default
	}

	/**
	 * Notification that a source item was pulled into the copy during Phase 2 —
	 * it existed only in the source and was added to the copy.
	 *
	 * @param sourceItem the item pulled from the source into the copy
	 */
	default void onPulledFromSourceToCopy(S sourceItem) {
		// no-op by default
	}

	/**
	 * Returns a listener that ignores every outcome. Used by phases that build no
	 * derived artifact.
	 *
	 * @param <C> the copy item type
	 * @param <S> the source item type
	 * @return a no-op listener
	 */
	static <C extends CopyItem, S extends SourceItem> SyncOutcomeListener<C, S> noOp() {
		return new SyncOutcomeListener<>() {
		};
	}

}
