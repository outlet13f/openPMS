package com.openpms.core;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/** Korean sort order for names (가나다), like the screens' localeCompare(…, "ko"). */
public final class Text {
    private Text() {}

    private static final Collator COLLATOR = Collator.getInstance(Locale.KOREAN);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final Comparator<String> KOREAN = (Comparator) COLLATOR;
}
