package com.exteragram.messenger.boost.encryption;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;

/** Заглушка закрытой части exteraGram: шифрования сообщений в этой сборке нет. */
public final class EncryptionHelper {

    public static final ArrayList<String> names = new ArrayList<>();

    public interface Callback {
        void run(String message);
    }

    private EncryptionHelper() {
    }

    public static boolean isEncrypted(String message) {
        return false;
    }

    public static boolean isEncrypted(MessageObject messageObject, MessageObject.GroupedMessages group) {
        return false;
    }

    public static MessageObject decryptMessage(MessageObject messageObject) {
        return messageObject;
    }

    public static MessageObject decryptMessage(MessageObject messageObject, MessageObject.GroupedMessages group) {
        return messageObject;
    }

    public static BaseEncryptor getEncryptorBy(long dialogId) {
        return new BaseEncryptor();
    }

    public static int getEncryptorTypeFor(long dialogId) {
        return 0;
    }

    public static void setEncryptorTypeFor(long dialogId, int type) {
    }

    public static void encryptMessage(String text, long dialogId, int type, Callback callback) {
        if (callback != null) {
            callback.run(text);
        }
    }

    public static String encryptMessage(String text, long dialogId, BaseEncryptor encryptor) {
        return text;
    }
}
