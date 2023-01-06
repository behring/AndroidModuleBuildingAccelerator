package plugins

import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import java.util.concurrent.TimeUnit

class TimingsListener : TaskExecutionListener, BuildListener {
    private var startTime: Long = 0L
    private val timings = mutableMapOf<Long, String>()

    override fun beforeExecute(task: Task) {
        startTime = System.nanoTime()
    }

    override fun afterExecute(task: Task, state: TaskState) {
        val ms = TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        timings[ms] = task.path
        println( "${task.path} took ${ms}ms")
    }

    override fun settingsEvaluated(settings: Settings) {
    }

    override fun projectsLoaded(gradle: Gradle) {
    }

    override fun projectsEvaluated(gradle: Gradle) {
    }

    override fun buildFinished(result: BuildResult) {
        println("Task timings:")
        timings.forEach { (ms, task) ->
            println("${ms}ms      $task")
        }
    }
}