package com.hulk.pillsapp

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.hulk.pillsapp.ledger.DbCrypto
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class SensitiveProfileOrigin {
    LEGACY_CONFIRMED,
    USER_SELECTED,
    WARNING_CONFIRMED,
}

internal enum class SensitiveWarningKind {
    SCREEN_CAPTURE_WARNING,
    ACCESSIBILITY_WARNING,
}

internal data class SensitiveAppIdentity(
    val packageName: String,
    val label: String,
    val signingDigest: String,
    val versionCode: Long,
    val userSerial: Long,
) {
    fun samePrincipalAs(other: SensitiveAppIdentity): Boolean =
        packageName == other.packageName && signingDigest == other.signingDigest &&
            userSerial == other.userSerial
}

internal data class SensitiveAppProfile(
    val identity: SensitiveAppIdentity,
    val origin: SensitiveProfileOrigin,
    val confirmedAtMs: Long,
)

internal data class SensitiveAppSuggestion(
    val identity: SensitiveAppIdentity,
    val reason: SensitiveWarningKind,
    val detectedAtMs: Long,
)

internal data class LaunchableApp(
    val identity: SensitiveAppIdentity,
) {
    val packageName: String get() = identity.packageName
    val label: String get() = identity.label
}

internal data class SensitiveProfileView(
    val profile: SensitiveAppProfile,
    val identityStillValid: Boolean,
    val installed: Boolean,
)

internal enum class SensitiveProfileLookupKind { ACTIVE, VERIFIED_NON_SENSITIVE, NOT_READY }

internal data class SensitiveProfileLookup(
    val kind: SensitiveProfileLookupKind,
    val profile: SensitiveAppProfile? = null,
)

/** 供系统回调读取的纯内存三态索引；空值绝不能被解释成“确定不是敏感应用”。 */
internal class SensitiveFastProfileIndex {
    private data class Snapshot(
        val ready: Boolean,
        val active: Map<String, SensitiveAppProfile>,
        val blocked: Set<String>,
    )

    private val snapshot = AtomicReference(Snapshot(false, emptyMap(), emptySet()))

    fun lookup(packageName: String): SensitiveProfileLookup {
        val current = snapshot.get()
        current.active[packageName]?.let {
            return SensitiveProfileLookup(SensitiveProfileLookupKind.ACTIVE, it)
        }
        return when {
            !current.ready || packageName in current.blocked ->
                SensitiveProfileLookup(SensitiveProfileLookupKind.NOT_READY)
            else -> SensitiveProfileLookup(SensitiveProfileLookupKind.VERIFIED_NON_SENSITIVE)
        }
    }

    fun replace(activeProfiles: List<SensitiveAppProfile>, blockedPackages: Set<String>) {
        val active = activeProfiles.associateBy { it.identity.packageName }
        snapshot.set(Snapshot(true, active, blockedPackages - active.keys))
    }

    fun activate(profile: SensitiveAppProfile) {
        while (true) {
            val current = snapshot.get()
            val packageName = profile.identity.packageName
            val updated = current.copy(
                active = current.active + (packageName to profile),
                blocked = current.blocked - packageName,
            )
            if (snapshot.compareAndSet(current, updated)) return
        }
    }

    fun block(packageName: String) {
        while (true) {
            val current = snapshot.get()
            val updated = current.copy(
                active = current.active - packageName,
                blocked = current.blocked + packageName,
            )
            if (snapshot.compareAndSet(current, updated)) return
        }
    }

    fun invalidate(packageName: String?) {
        if (packageName == null) {
            reset()
        } else {
            block(packageName)
        }
    }

    fun reset() {
        snapshot.set(Snapshot(false, emptyMap(), emptySet()))
    }
}

/**
 * 加密的本地敏感 App 注册表。只有用户确认过且签名/Android 用户身份仍匹配的包才生效；
 * 警告关键词最多生成建议，绝不自动停用无障碍或启动第三方 App。
 */
internal object SensitiveAppRegistry {
    private const val MAGIC = 0x53415031 // SAP1
    private const val FORMAT_VERSION = 1
    private const val FILE_NAME = "sensitive_app_registry.enc"
    private const val MAX_PROFILES = 200
    private const val LEGACY_CMB_PACKAGE = "cmb.pb"
    private const val LEGACY_CMB_LABEL = "招商银行"

    private data class Snapshot(
        val defaultsMigrated: Boolean,
        val profiles: List<SensitiveAppProfile>,
        val suggestion: SensitiveAppSuggestion?,
    )

    private var cached: Snapshot? = null
    private var loadFailed = false
    private val identityCache = mutableMapOf<String, SensitiveAppIdentity?>()
    private val fastIndex = SensitiveFastProfileIndex()

    @Synchronized
    fun profiles(context: Context): List<SensitiveProfileView> {
        val snapshot = load(context)
        if (loadFailed) {
            fastIndex.reset()
            return emptyList()
        }
        val views = snapshot.profiles
            .filter { it.identity.userSerial == PackageIdentityResolver.userSerial(context) }
            .map { profile ->
                val current = resolveCached(context, profile.identity.packageName)
                SensitiveProfileView(
                    profile = profile,
                    installed = current != null,
                    identityStillValid = current?.signingDigest == profile.identity.signingDigest &&
                        current.userSerial == profile.identity.userSerial,
                )
            }
            .sortedBy { it.profile.identity.label }
        fastIndex.replace(
            activeProfiles = views.filter { it.installed && it.identityStillValid }.map { it.profile },
            blockedPackages = views.filterNot { it.installed && it.identityStillValid }
                .map { it.profile.identity.packageName }.toSet(),
        )
        return views
    }

    @Synchronized
    fun activeProfile(context: Context, packageName: String): SensitiveAppProfile? {
        val snapshot = load(context)
        if (loadFailed) return null
        val profile = snapshot.profiles.firstOrNull {
            it.identity.packageName == packageName &&
                it.identity.userSerial == PackageIdentityResolver.userSerial(context)
        } ?: return null
        val current = resolveCached(context, packageName)
        if (current == null) {
            fastIndex.block(packageName)
            return null
        }
        val active = profile.takeIf {
            current.signingDigest == it.identity.signingDigest &&
                current.userSerial == it.identity.userSerial
        }
        if (active == null) fastIndex.block(packageName) else fastIndex.activate(active)
        return active
    }

    /** 系统无障碍回调只读三态内存快照，不在回调内触发 Keystore、磁盘或包解析。 */
    fun lookupFast(packageName: String): SensitiveProfileLookup = fastIndex.lookup(packageName)

    @Synchronized
    fun suggestion(context: Context): SensitiveAppSuggestion? {
        val snapshot = load(context)
        return snapshot.suggestion.takeUnless { loadFailed }
    }

    @Synchronized
    fun add(
        context: Context,
        selectedIdentity: SensitiveAppIdentity,
        origin: SensitiveProfileOrigin,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        // 用户确认的是选择时看到的 principal，而不是一个可被卸载/替换复用的包名。
        val identity = PackageIdentityResolver.resolve(context, selectedIdentity.packageName) ?: return false
        if (!identity.samePrincipalAs(selectedIdentity)) return false
        val snapshot = load(context)
        if (loadFailed) return false
        val updated = snapshot.profiles.filterNot {
            it.identity.packageName == identity.packageName && it.identity.userSerial == identity.userSerial
        } + SensitiveAppProfile(identity, origin, nowMs)
        val profile = SensitiveAppProfile(identity, origin, nowMs)
        val persisted = persist(
            context,
            snapshot.copy(
                profiles = updated,
                suggestion = snapshot.suggestion?.takeUnless {
                    it.identity.packageName == identity.packageName && it.identity.userSerial == identity.userSerial
                },
            )
        )
        if (persisted) fastIndex.activate(profile)
        return persisted
    }

    @Synchronized
    fun remove(context: Context, packageName: String): Boolean {
        val userSerial = PackageIdentityResolver.userSerial(context)
        val snapshot = load(context)
        if (loadFailed) return false
        return persist(
            context,
            snapshot.copy(
                profiles = snapshot.profiles.filterNot {
                    it.identity.packageName == packageName && it.identity.userSerial == userSerial
                },
            )
        )
    }

    @Synchronized
    fun propose(
        context: Context,
        packageName: String,
        reason: SensitiveWarningKind,
        nowMs: Long = System.currentTimeMillis(),
    ): SensitiveAppSuggestion? {
        if (activeProfile(context, packageName) != null) return null
        val identity = PackageIdentityResolver.resolve(context, packageName) ?: return null
        val snapshot = load(context)
        if (loadFailed) return null
        val existing = snapshot.suggestion
        if (existing?.identity?.packageName == packageName &&
            existing.identity.signingDigest == identity.signingDigest
        ) {
            return null
        }
        val suggestion = SensitiveAppSuggestion(identity, reason, nowMs)
        return if (persist(context, snapshot.copy(suggestion = suggestion))) suggestion else null
    }

    @Synchronized
    fun confirmSuggestion(
        context: Context,
        expectedSuggestion: SensitiveAppSuggestion,
    ): Boolean {
        val snapshot = load(context)
        if (loadFailed) return false
        val current = snapshot.suggestion ?: return false
        if (current != expectedSuggestion) return false
        return add(context, current.identity, SensitiveProfileOrigin.WARNING_CONFIRMED)
    }

    @Synchronized
    fun dismissSuggestion(context: Context): Boolean {
        val snapshot = load(context)
        if (loadFailed) return false
        if (snapshot.suggestion == null) return true
        return persist(context, snapshot.copy(suggestion = null))
    }

    @Synchronized
    fun isCurrentSuggestion(context: Context, expected: SensitiveAppSuggestion): Boolean {
        val snapshot = load(context)
        return !loadFailed && snapshot.suggestion == expected
    }

    @Synchronized
    fun invalidate(packageName: String?) {
        if (packageName == null) {
            identityCache.clear()
        } else {
            identityCache.remove(packageName)
        }
        fastIndex.invalidate(packageName)
    }

    @Synchronized
    fun storageHealthy(context: Context): Boolean {
        load(context)
        return !loadFailed
    }

    private fun resolveCached(context: Context, packageName: String): SensitiveAppIdentity? {
        if (!identityCache.containsKey(packageName)) {
            identityCache[packageName] = PackageIdentityResolver.resolve(context, packageName)
        }
        return identityCache[packageName]
    }

    private fun load(context: Context): Snapshot {
        cached?.let { return it }
        val appContext = context.applicationContext
        val file = File(appContext.filesDir, FILE_NAME)
        val loaded = if (file.exists()) {
            runCatching { read(file) }
                .onFailure { loadFailed = true }
                .getOrNull()
        } else {
            null
        }
        if (loaded != null) {
            cached = loaded
            return loaded
        }
        if (file.exists()) {
            return Snapshot(defaultsMigrated = true, profiles = emptyList(), suggestion = null)
        }
        val legacy = PackageIdentityResolver.resolve(appContext, LEGACY_CMB_PACKAGE)?.let {
            SensitiveAppProfile(it.copy(label = LEGACY_CMB_LABEL), SensitiveProfileOrigin.LEGACY_CONFIRMED, System.currentTimeMillis())
        }
        val initial = Snapshot(
            defaultsMigrated = true,
            profiles = listOfNotNull(legacy),
            suggestion = null,
        )
        if (!persist(appContext, initial)) loadFailed = true
        return cached ?: initial
    }

    private fun persist(context: Context, snapshot: Snapshot): Boolean {
        if (snapshot.profiles.size > MAX_PROFILES) return false
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        return try {
            val plain = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(BufferedOutputStream(bytes)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeBoolean(snapshot.defaultsMigrated)
                    output.writeInt(snapshot.profiles.size)
                    snapshot.profiles.forEach { output.writeProfile(it) }
                    output.writeBoolean(snapshot.suggestion != null)
                    snapshot.suggestion?.let { output.writeSuggestion(it) }
                    output.flush()
                }
                bytes.toByteArray()
            }
            val encrypted = DbCrypto.encryptLocalArtifact(plain)
            FileOutputStream(temporary).use { stream ->
                stream.write(encrypted)
                stream.flush()
                stream.fd.sync()
            }
            android.system.Os.rename(temporary.absolutePath, target.absolutePath)
            syncDirectory(requireNotNull(target.parentFile))
            cached = snapshot
            loadFailed = false
            rebuildFastIndex(context.applicationContext, snapshot)
            true
        } catch (_: Throwable) {
            // 写入可能已经越过 rename 但尚未完成目录同步。此时内存与磁盘谁是最新值
            // 无法可靠判断，必须停止后续编辑，等待下次进程启动重新读取，而不是继续覆盖。
            cached = null
            loadFailed = true
            identityCache.clear()
            fastIndex.reset()
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun rebuildFastIndex(context: Context, snapshot: Snapshot) {
        val currentUser = PackageIdentityResolver.userSerial(context)
        val active = mutableListOf<SensitiveAppProfile>()
        val blocked = mutableSetOf<String>()
        snapshot.profiles.filter { it.identity.userSerial == currentUser }.forEach { profile ->
            val current = PackageIdentityResolver.resolve(context, profile.identity.packageName)
            if (current != null && current.samePrincipalAs(profile.identity)) {
                active += profile
            } else {
                blocked += profile.identity.packageName
            }
        }
        fastIndex.replace(active, blocked)
    }

    private fun read(file: File): Snapshot = DataInputStream(
        BufferedInputStream(
            ByteArrayInputStream(DbCrypto.decryptLocalArtifact(FileInputStream(file).use { it.readBytes() }))
        )
    ).use { input ->
        check(input.readInt() == MAGIC) { "invalid sensitive registry magic" }
        check(input.readInt() == FORMAT_VERSION) { "unsupported sensitive registry version" }
        val migrated = input.readBoolean()
        val count = input.readInt()
        check(count in 0..MAX_PROFILES) { "invalid sensitive registry size" }
        val profiles = List(count) { input.readProfile() }
        val suggestion = if (input.readBoolean()) input.readSuggestion() else null
        check(input.read() == -1) { "trailing sensitive registry bytes" }
        Snapshot(migrated, profiles, suggestion)
    }

    private fun DataOutputStream.writeIdentity(identity: SensitiveAppIdentity) {
        writeUTF(identity.packageName)
        writeUTF(identity.label.take(200))
        writeUTF(identity.signingDigest)
        writeLong(identity.versionCode)
        writeLong(identity.userSerial)
    }

    private fun DataInputStream.readIdentity(): SensitiveAppIdentity = SensitiveAppIdentity(
        packageName = readUTF(),
        label = readUTF(),
        signingDigest = readUTF(),
        versionCode = readLong(),
        userSerial = readLong(),
    )

    private fun DataOutputStream.writeProfile(profile: SensitiveAppProfile) {
        writeIdentity(profile.identity)
        writeUTF(profile.origin.name)
        writeLong(profile.confirmedAtMs)
    }

    private fun DataInputStream.readProfile(): SensitiveAppProfile = SensitiveAppProfile(
        identity = readIdentity(),
        origin = SensitiveProfileOrigin.valueOf(readUTF()),
        confirmedAtMs = readLong(),
    )

    private fun DataOutputStream.writeSuggestion(suggestion: SensitiveAppSuggestion) {
        writeIdentity(suggestion.identity)
        writeUTF(suggestion.reason.name)
        writeLong(suggestion.detectedAtMs)
    }

    private fun DataInputStream.readSuggestion(): SensitiveAppSuggestion = SensitiveAppSuggestion(
        identity = readIdentity(),
        reason = SensitiveWarningKind.valueOf(readUTF()),
        detectedAtMs = readLong(),
    )

    private fun syncDirectory(directory: File) {
        val directoryFd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(directoryFd)
        } finally {
            android.system.Os.close(directoryFd)
        }
    }
}

internal object PackageIdentityResolver {
    fun userSerial(context: Context): Long = runCatching {
        context.getSystemService(UserManager::class.java)
            .getSerialNumberForUser(Process.myUserHandle())
    }.getOrDefault(-1L)

    fun resolve(context: Context, packageName: String): SensitiveAppIdentity? = runCatching {
        val packageInfo = packageInfo(context.packageManager, packageName)
        val applicationInfo = packageInfo.applicationInfo ?: return@runCatching null
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty().map { it.toByteArray() }
        }
        if (signatures.isEmpty()) return@runCatching null
        val digest = signatures.map(::sha256).sorted().joinToString(":")
        SensitiveAppIdentity(
            packageName = packageName,
            label = context.packageManager.getApplicationLabel(applicationInfo).toString().take(200),
            signingDigest = digest,
            versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            userSerial = userSerial(context),
        )
    }.getOrNull()

    fun launchableApps(context: Context): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            PackageIdentityResolver.resolve(context, packageName)?.let(::LaunchableApp)
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    fun launchActivityMayUsePictureInPicture(context: Context, packageName: String): Boolean =
        runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }
            packageInfo.activities.orEmpty().any {
                it.flags and ACTIVITY_FLAG_SUPPORTS_PICTURE_IN_PICTURE != 0
            }
        }.getOrDefault(false)

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
            )
        }

    // Framework ActivityInfo 中的稳定 manifest 标志位；Android 34 public SDK 未导出常量名。
    // 只用于 fail-safe 地禁止对可进入 PiP 的敏感应用自动恢复监视。
    private const val ACTIVITY_FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x00400000

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

internal object SensitiveWarningClassifier {
    fun classify(texts: List<String>): SensitiveWarningKind? {
        val text = texts.joinToString(" ").replace(Regex("\\s+"), " ")
        val screenSignal = listOf(
            "共享/录制屏幕", "共享屏幕", "录制屏幕", "屏幕录制", "正在录屏", "投屏",
        ).any(text::contains)
        val accessibilitySignal = listOf("无障碍", "辅助功能").any(text::contains)
        val securityContext = listOf(
            "检测到", "账户安全", "为了您的安全", "请您关闭", "关闭上述功能", "存在风险", "不支持",
        ).any(text::contains)
        return when {
            screenSignal && securityContext -> SensitiveWarningKind.SCREEN_CAPTURE_WARNING
            accessibilitySignal && securityContext -> SensitiveWarningKind.ACCESSIBILITY_WARNING
            else -> null
        }
    }
}

internal object UsageAccessState {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun recentExternalApp(context: Context, nowMs: Long = System.currentTimeMillis()): LaunchableApp? {
        if (!isGranted(context)) return null
        val events = runCatching {
            context.getSystemService(UsageStatsManager::class.java)
                .queryEvents(nowMs - 10 * 60 * 1000L, nowMs)
        }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestAt = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if ((event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) &&
                event.timeStamp >= latestAt && event.packageName != context.packageName
            ) {
                latestAt = event.timeStamp
                latestPackage = event.packageName
            }
        }
        val packageName = latestPackage ?: return null
        return PackageIdentityResolver.resolve(context, packageName)?.let(::LaunchableApp)
    }
}

internal enum class SensitiveMonitorAction { NONE, TARGET_ENTERED, PROMPT_EXIT_CONFIRMATION }

/** UsageEvents 只升级人工确认提示，永远不直接触发恢复。 */
internal class SensitiveDepartureTracker(
    private val targetPackage: String,
    targetEnteredInitially: Boolean = false,
    promptedSinceLastTargetInitially: Boolean = false,
) {
    private var targetEntered = targetEnteredInitially
    private var promptedSinceLastTarget = promptedSinceLastTargetInitially

    fun observe(
        packageName: String?,
        selfPackage: String,
        transientPackages: Set<String>,
    ): SensitiveMonitorAction {
        if (packageName.isNullOrBlank()) return SensitiveMonitorAction.NONE
        if (packageName == targetPackage) {
            promptedSinceLastTarget = false
            if (targetEntered) return SensitiveMonitorAction.TARGET_ENTERED
            targetEntered = true
            return SensitiveMonitorAction.TARGET_ENTERED
        }
        if (!targetEntered || packageName == selfPackage || packageName in transientPackages) {
            return SensitiveMonitorAction.NONE
        }
        if (promptedSinceLastTarget) return SensitiveMonitorAction.NONE
        promptedSinceLastTarget = true
        return SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION
    }
}

class SensitivePackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SensitiveAppRegistry.invalidate(intent.data?.schemeSpecificPart)
    }
}
