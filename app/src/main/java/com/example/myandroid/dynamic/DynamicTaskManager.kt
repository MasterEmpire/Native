package com.example.myandroid.dynamic

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.*
import java.io.File
import com.example.myandroid.DebugLogger

object DynamicTaskManager {
    private val parentJob = SupervisorJob()
    private val taskScope = CoroutineScope(Dispatchers.IO + parentJob)
    private val activeTasks = mutableMapOf<String, DynamicEntry>()
    private val activeJobs = mutableMapOf<String, Job>()

    fun executeHeadlessTask(context: Context, dirPath: String, className: String, bridge: Any, timeoutMins: Long = 15L) {
        val job = taskScope.launch {
            try {
                val trapDir = File(dirPath)
                val dexFile = File(trapDir, "classes.dex")
                if (!dexFile.exists()) {
                    DebugLogger.log("TASK_MGR_ERR", "DEX file missing: ${dexFile.absolutePath}")
                    return@launch
                }

                val optDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
                val loader = DexClassLoader(
                    dexFile.absolutePath,
                    optDir.absolutePath,
                    null,
                    context.classLoader
                )

                val clazz = loader.loadClass(className)
                val instance = clazz.getDeclaredConstructor().newInstance() as DynamicEntry

                synchronized(activeTasks) {
                    activeTasks[className] = instance
                }

                DebugLogger.log("TASK_MGR", "Invoking onStart() for headless task: $className")
                instance.onStart(context.applicationContext, bridge, trapDir.absolutePath)

                // Optional Timeout Failsafe
                if (timeoutMins > 0) {
                    delay(timeoutMins * 60 * 1000L)
                    DebugLogger.log("TASK_MGR", "Headless task $className exceeded timeout. Terminating.")
                    stopTask(context, className)
                }

            } catch (e: Exception) {
                DebugLogger.log("TASK_MGR_ERR", "Task $className failed: ${e.message}")
            }
        }

        synchronized(activeJobs) {
            activeJobs[className]?.cancel()
            activeJobs[className] = job
        }
    }

    fun stopTask(context: Context, className: String) {
        synchronized(activeJobs) {
            activeJobs[className]?.cancel()
            activeJobs.remove(className)
        }
        synchronized(activeTasks) {
            activeTasks[className]?.let {
                try {
                    it.onStop(context.applicationContext)
                    DebugLogger.log("TASK_MGR", "Task onStop() cleanly executed: $className")
                } catch (e: Exception) {
                    DebugLogger.log("TASK_MGR_ERR", "Task cleanup failed: ${e.message}")
                }
                activeTasks.remove(className)
            }
        }
    }
}
