package io.webagent4j.browser;

import java.util.List;

/**
 * Backend-neutral owner of a browser process and its isolated context.
 *
 * <p>Browser instances are not thread-safe and must be closed by their owner.
 */
public interface IBrowser extends AutoCloseable {

    /** Creates a blank page. */
    IPage newPage();

    /** Opens a new page and navigates it to the supplied HTTP(S) URL. */
    IPage open(String url);

    /** Returns the most recently created page. */
    IPage currentPage();

    /** Returns an immutable snapshot of currently open pages. */
    List<IPage> pages();

    /** Closes all pages, the isolated context, and the underlying browser process. */
    @Override
    void close();
}
