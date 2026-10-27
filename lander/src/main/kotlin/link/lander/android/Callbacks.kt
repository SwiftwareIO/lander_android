package link.lander.android

/** Receives deep links on the main thread. See [Lander.addDeepLinkListener]. */
public fun interface LanderLinkListener {
    public fun onDeepLink(link: LanderLink)
}

/** Stops a [LanderLinkListener]. */
public fun interface LanderSubscription {
    public fun remove()
}

/**
 * The result of an asynchronous call made from Java (or Kotlin without
 * coroutines). Exactly one method is called, on the main thread.
 */
public interface LanderCallback<T> {
    public fun onSuccess(result: T)

    /**
     * [error] is a [LanderException] for API and network failures, or an
     * [IllegalArgumentException] / [IllegalStateException] for programming
     * errors.
     */
    public fun onError(error: Exception)
}
