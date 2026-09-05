package com.moltenbits.sideband.push;

import com.moltenbits.sideband.journal.Entry;

import java.nio.file.Path;
import java.util.List;

/**
 * Delivery performed by the writer. After an entry is appended, each client recipient
 * whose host can be woken from outside receives the envelope immediately and is marked
 * delivered; every other recipient's own listener picks the entry up.
 */
public interface Pushes {

    List<PushResult> deliver(Path stateDirectory, Entry entry);
}
