package io.wallpaper.soundfix

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 场景壁纸 Sound 对象的 Java 播放桥。
 * 由 native 层（soundfix.cpp 虚表替换）通过 JNI 调用。
 * 所有方法都在渲染线程上串行调用，无需复杂同步。
 */
object SoundBridge {

    /** 用户音量系数 (0~1)，由 HookEntry 从 SharedPreferences 同步。 */
    @Volatile
    var volumeFactor = 1f

    private const val TAG = "WESoundFix"

    private val players = ConcurrentHashMap<Long, MediaPlayer>()
    private val pausedIds = ConcurrentHashMap<Long, Boolean>()
    private val nextId = AtomicLong(1)

    /** 判断 MediaPlayer 是否由本桥创建（供 HookEntry 的 setVolume hook 排除，避免误拦截）。 */
    fun isBridgePlayer(mp: MediaPlayer?): Boolean =
        mp != null && players.containsValue(mp)

    /** native: CreateSound。用内存数据源创建 MediaPlayer，返回 0 表示失败。 */
    fun createSound(data: ByteArray): Long = try {
        val mp = MediaPlayer()
        mp.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= data.size) return -1
                val n = minOf(size.toLong(), data.size - position).toInt()
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                return n
            }
            override fun getSize(): Long = data.size.toLong()
            override fun close() {}
        })
        mp.isLooping = true
        mp.prepare()
        val id = nextId.getAndIncrement()
        players[id] = mp
        Log.i(TAG, "sound created: id=$id duration=${mp.duration}ms")
        id
    } catch (t: Throwable) {
        Log.e(TAG, "createSound failed", t)
        0L
    }

    /** native: Play。 */
    fun play(id: Long) {
        pausedIds.remove(id)
        players[id]?.takeIf { !it.isPlaying }?.start()
    }

    /** native: Pause。 */
    fun pause(id: Long) {
        players[id]?.takeIf { it.isPlaying }?.let {
            it.pause()
            pausedIds[id] = true
        }
    }

    /** native: Stop。用 pause+seekTo(0) 实现，规避 MediaPlayer stop 后需重新 prepare 的状态机。 */
    fun stop(id: Long) {
        pausedIds.remove(id)
        players[id]?.let {
            if (it.isPlaying) it.pause()
            it.seekTo(0)
        }
    }

    /** native: SetVolume。vol 为引擎计算的最终音量 (sound² × global)，再乘用户系数。 */
    fun setVolume(id: Long, vol: Float) {
        val v = vol.coerceIn(0f, 1f) * volumeFactor
        players[id]?.setVolume(v, v)
    }

    /** native: GetDuration，返回秒。 */
    fun getDuration(id: Long): Double =
        players[id]?.let { it.duration / 1000.0 } ?: 0.0

    /** native: IsPlaying。 */
    fun isPlaying(id: Long): Boolean =
        players[id]?.isPlaying == true

    /** native: IsPaused。 */
    fun isPaused(id: Long): Boolean =
        pausedIds[id] == true

    /** native: IsStopped。 */
    fun isStopped(id: Long): Boolean =
        players[id]?.let { !it.isPlaying && !pausedIds.contains(id) } ?: true

    /** native: DestroySound。 */
    fun destroy(id: Long) {
        players.remove(id)?.let {
            pausedIds.remove(id)
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 壁纸整体暂停（离开桌面/省电）：暂停所有播放中的声音。
     * native 层 Main::SetPaused / Sound::PauseSounds 从未被调用（原应用未接线），
     * 由 HookEntry.updatePausedState hook 在 Java 层驱动。
     */
    fun pauseAll() {
        for ((id, mp) in players) {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    pausedIds[id] = true
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 壁纸整体恢复（回到桌面）：恢复被 pauseAll 暂停的声音。 */
    fun resumeAll() {
        for (id in pausedIds.keys.toList()) {
            try {
                players[id]?.start()
            } catch (_: Throwable) {
            }
        }
        pausedIds.clear()
    }
}
