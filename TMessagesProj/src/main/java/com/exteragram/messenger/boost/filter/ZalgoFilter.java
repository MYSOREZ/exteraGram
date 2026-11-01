package com.exteragram.messenger.boost.filter;

/** Заглушка закрытой части exteraGram: текст не меняется. */
public final class ZalgoFilter {
    private ZalgoFilter() {
    }

    public static CharSequence filterMessage(CharSequence cs) {
        return cs;
    }
}
