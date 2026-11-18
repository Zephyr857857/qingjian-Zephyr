# JNI 入口：Rust 侧按 Java_app_qingjian_inputmethod_core_Native_* 的符号名找方法，
# 类名与方法名都不能被 R8 改掉，否则 System.loadLibrary 后调用全部 UnsatisfiedLinkError。
-keep class app.qingjian.inputmethod.core.Native {
    native <methods>;
    public static final app.qingjian.inputmethod.core.Native INSTANCE;
}

# 从 JNI 回传的 JSON 由 org.json 手动解析（Frame/StripMeta 等），不经反射；
# 保留 InputMethodService 子类，系统在 manifest 里按类名实例化，别被 R8 合并/改名。
-keep public class app.qingjian.inputmethod.service.QingjianInputMethodService
-keep public class app.qingjian.inputmethod.translate.ProcessTextActivity
-keep public class app.qingjian.inputmethod.voice.MicrophonePermissionActivity
-keep public class app.qingjian.inputmethod.clipboard.ClipboardImageProvider
-keep public class app.qingjian.inputmethod.settings.page.LogShareProvider
