package is.xyz.mpv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log

@RunWith(AndroidJUnit4::class)
class MPVNetworkTest {

    private val syncTag = "SyncTVTest"

    // Short-term manual diagnosis URL (expires in the future)
    private val testCdnUrl = "https://upos-sz-estgcos.bilivideo.com/upgcxcode/02/60/38688196002/38688196002-1-192.mp4?e=ig8euxZM2rNcNbRVhwdVhwdlhWdVhwdVhoNvNC8BqJIzNbfq9rVEuxTEnE8L5F6VnEsSTx0vkX8fqJeYTj_lta53NCM=&uipk=5&mid=0&gen=playurlv3&os=estgcos&og=cos&trid=224a5b355e1b47bbb5b791bd54576e8h&platform=html5&deadline=1780118419&nbs=1&oi=1887184075&upsig=59b124cb1dfbc554528b2a1c4ff6600a&uparams=e,uipk,mid,gen,os,og,trid,platform,deadline,nbs,oi&bvc=vod&nettype=0&bw=762627&lrs=0&buvid=&build=0&dl=0&f=h_0_0&agrr=0&orderid=0,1"

    @Test
    fun testBilibiliCdnNetwork_StandardHeaders() {
        runHeadlessNetworkTest(
            testName = "Standard Headers Test",
            disableIcy = false
        )
    }

    @Test
    fun testBilibiliCdnNetwork_DisableIcy() {
        runHeadlessNetworkTest(
            testName = "Disable ICY Header Test (icy=0)",
            disableIcy = true
        )
    }

    private fun runHeadlessNetworkTest(testName: String, disableIcy: Boolean) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        
        var http400Detected = false
        var http403Detected = false
        var playbackSuccess = false

        Log.d(syncTag, "=== Starting Connected Headless Integration Test [ $testName ] ===")

        // 1. Register log observer
        val logObserver = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                val logLine = text.trim()
                Log.d(syncTag, "[$testName][Log][$prefix] $logLine")
                
                if (logLine.contains("HTTP error 400", ignoreCase = true) || 
                    logLine.contains("Server returned 400", ignoreCase = true)) {
                    http400Detected = true
                }
                if (logLine.contains("HTTP error 403", ignoreCase = true) || 
                    logLine.contains("Server returned 403", ignoreCase = true)) {
                    http403Detected = true
                }
            }
        }
        MPVLib.addLogObserver(logObserver)

        // 2. Register event observer
        val eventObserver = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {}
            override fun event(eventId: Int) {
                Log.d(syncTag, "[$testName][Event] Received eventId: $eventId")
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        playbackSuccess = true
                        Log.d(syncTag, "[$testName][Event] Playback loaded successfully!")
                        latch.countDown()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                        Log.e(syncTag, "[$testName][Event] Native libmpv fired END_FILE.")
                        latch.countDown()
                    }
                }
            }
        }
        MPVLib.addObserver(eventObserver)

        // 3. Initialize Headless libmpv
        try {
            MPVLib.create(appContext)
            
            // Headless optimizations to disable video/audio outputs
            MPVLib.setOptionString("vo", "null")
            MPVLib.setOptionString("ao", "null")
            MPVLib.setOptionString("video", "no")
            MPVLib.setOptionString("audio", "no")
            
            MPVLib.init()
            Log.d(syncTag, "[$testName] Headless libmpv initialized.")
        } catch (e: Exception) {
            fail("[$testName] Failed to initialize libmpv: ${e.message}")
            return
        }

        // 4. Configure headers
        val headersList = mutableListOf(
            "Referer: https://www.bilibili.com/",
            "Origin: https://www.bilibili.com",
            "Accept: */*",
            "Accept-Encoding: identity"
        )
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        MPVLib.setPropertyString("user-agent", userAgent)
        MPVLib.setPropertyString("http-header-fields", headersList.joinToString(","))

        // 5. Apply icy=0 if requested
        if (disableIcy) {
            MPVLib.setPropertyString("demuxer-lavf-o", "icy=0")
            Log.d(syncTag, "[$testName] Applied option: demuxer-lavf-o = icy=0")
        }

        // 6. Trigger loadfile
        Log.d(syncTag, "[$testName] Loading URL: $testCdnUrl")
        MPVLib.command(arrayOf("loadfile", testCdnUrl, "replace"))

        // 7. Block for up to 15 seconds
        latch.await(15, TimeUnit.SECONDS)

        // 8. Cleanup
        MPVLib.removeLogObserver(logObserver)
        MPVLib.removeObserver(eventObserver)
        MPVLib.destroy()
        Log.d(syncTag, "[$testName] Native libmpv destroyed.")

        // 9. Assertions
        if (http403Detected) {
            fail("[$testName] FAILED: URL expired or signature invalid (HTTP 403). Update the deadline URL in code.")
        } else if (playbackSuccess) {
            Log.d(syncTag, "[$testName] PASSED: Network stream opened successfully!")
        } else {
            assertTrue(
                "[$testName] FAILED: Playback aborted/failed without receiving HTTP 400. Check parameters or connection.",
                http400Detected
            )
            Log.d(syncTag, "[$testName] SUCCESSFUL REPRODUCTION: Native HTTP 400 error caught as expected!")
        }
    }
}
