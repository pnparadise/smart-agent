package com.smart.component

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.smart.R
import java.lang.ref.WeakReference

/**
 * 仿 Flutter 风格的全局 Toast
 */
object SmartToast {

    private var dismissHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var activityRef: WeakReference<Activity>? = null
    private var lifecycleRegistered = false
    private val pendingQueue = mutableListOf<PendingToast>()

    // 记录当前显示在 WindowManager 中的 View
    private var currentToastView: View? = null

    // 记录当前依附的 Activity，用于判断是否需要清理
    private var currentAttachedActivity: WeakReference<Activity>? = null

    private data class PendingToast(
        val msg: String,
        @DrawableRes val iconRes: Int,
        val type: ToastType,
        val duration: Long
    )

    // 样式常量
    private const val BG_COLOR = "#B3111111" // 70% 透明度深黑
    private const val CORNER_RADIUS_DP = 10f
    private const val SQUARE_SIZE_DP = 120
    private const val ICON_SIZE_DP = 38
    private const val TEXT_SIZE_SP = 14f

    enum class ToastType {
        SQUARE, // 方形 (图标 + 文字)
        NORMAL  // 长条形 (纯文字)
    }

    // ================= 公开方法 =================

    fun showSuccess(context: Context, msg: String, duration: Long = 1500) {
        show(context, msg, R.drawable.ic_toast_success, ToastType.SQUARE, duration)
    }

    fun showInfo(context: Context, msg: String, duration: Long = 1500) {
        show(context, msg, R.drawable.ic_toast_info, ToastType.SQUARE, duration)
    }

    fun showFailure(context: Context, msg: String, duration: Long = 1500) {
        show(context, msg, R.drawable.ic_toast_failure, ToastType.SQUARE, duration)
    }

    fun showText(context: Context, msg: String, duration: Long = 3000) {
        show(context, msg, 0, ToastType.NORMAL, duration)
    }


    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        // 既然 Activity 来了，赶紧看看有没有积压的任务
        if (pendingQueue.isNotEmpty()) {
            activity.runOnUiThread {
                flushPending(activity)
            }
        }
    }

    // ================= 内部实现 =================

    private fun show(
        context: Context,
        msg: String,
        @DrawableRes iconRes: Int,
        type: ToastType,
        duration: Long
    ) {
        val activity = resolveActivity(context)
        if (activity == null) {
            enqueuePending(msg, iconRes, type, duration, context)
            return
        }

        activity.runOnUiThread {
            showWithWindowManager(activity, msg, iconRes, type, duration)
        }
    }

    private fun showWithWindowManager(
        activity: Activity,
        msg: String,
        @DrawableRes iconRes: Int,
        toastType: ToastType, // 【修复】：重命名参数，防止与 LayoutParams.type 冲突
        duration: Long
    ) {
        // 1. 立即清理旧 View
        removeNow()

        if (activity.isFinishing || activity.isDestroyed) return

        val windowManager = activity.windowManager ?: return

        // 2. 创建 View
        val toastView = createToastView(activity, msg, iconRes, toastType)

        // 3. 配置 WindowManager 参数
        val params = WindowManager.LayoutParams().apply {
            height = WindowManager.LayoutParams.WRAP_CONTENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            windowAnimations = android.R.style.Animation_Toast

            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            gravity = Gravity.CENTER

            // 【此处原本报错】：现在 type 指的是 WindowManager.LayoutParams.type (Int)
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        }

        // 4. 针对不同类型的尺寸微调
        if (toastType == ToastType.SQUARE) {
            val size = dp2px(activity, SQUARE_SIZE_DP.toFloat())
            params.width = size
            params.height = size
        } else {
            // NORMAL 模式
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        try {
            // 5. 添加到 WindowManager
            windowManager.addView(toastView, params)

            // 记录状态
            currentToastView = toastView
            currentAttachedActivity = WeakReference(activity)

            // 6. 进场动画
            toastView.alpha = 0f
            val fadeIn = ObjectAnimator.ofFloat(toastView, "alpha", 0f, 1f)
            fadeIn.duration = 200
            fadeIn.interpolator = AccelerateDecelerateInterpolator()
            fadeIn.start()

            // 7. 定时移除
            dismissRunnable = Runnable { dismiss(windowManager) }
            dismissHandler.postDelayed(dismissRunnable!!, duration)

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("SmartToast", "WindowManager addView failed: ${e.message}")
        }
    }

    private fun dismiss(wm: WindowManager) {
        val view = currentToastView ?: return

        val fadeOut = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
        fadeOut.duration = 300
        fadeOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    if (currentToastView == view) {
                        wm.removeViewImmediate(view)
                        currentToastView = null
                        currentAttachedActivity = null
                    }
                } catch (e: Exception) {
                    // View 可能已经被移除了
                }
            }
        })
        fadeOut.start()
    }

    private fun removeNow() {
        dismissRunnable?.let { dismissHandler.removeCallbacks(it) }

        val view = currentToastView
        val activity = currentAttachedActivity?.get()

        if (view != null && activity != null) {
            try {
                activity.windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                // Ignore
            }
        }
        currentToastView = null
        currentAttachedActivity = null
    }

    // ================= UI 构建 =================

    private fun createToastView(context: Context, msg: String, iconRes: Int, toastType: ToastType): View {
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor(BG_COLOR))
            cornerRadius = dp2px(context, CORNER_RADIUS_DP).toFloat()
        }

        val container = LinearLayout(context).apply {
            background = bgDrawable
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        if (toastType == ToastType.SQUARE) {
            val iconView = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val iconParams = LinearLayout.LayoutParams(
                dp2px(context, ICON_SIZE_DP.toFloat()),
                dp2px(context, ICON_SIZE_DP.toFloat())
            ).apply {
                bottomMargin = dp2px(context, 10f)
            }
            container.addView(iconView, iconParams)

            val textView = TextView(context).apply {
                text = msg
                setTextColor(Color.WHITE)
                textSize = TEXT_SIZE_SP
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val textParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp2px(context, 8f)
                rightMargin = dp2px(context, 8f)
            }
            container.addView(textView, textParams)

        } else {
            // NORMAL 模式
            val paddingH = dp2px(context, 24f)
            val paddingV = dp2px(context, 12f)
            container.setPadding(paddingH, paddingV, paddingH, paddingV)

            val textView = TextView(context).apply {
                text = msg
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER

                val displayMetrics = context.resources.displayMetrics
                maxWidth = displayMetrics.widthPixels - dp2px(context, 100f)
            }

            val textParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            container.addView(textView, textParams)
        }
        return container
    }

    // ================= 辅助方法 =================

    private fun resolveActivity(context: Context): Activity? {
        // 1. 优先使用缓存
        activityRef?.get()?.let { return it }

        // 2. 尝试从 Context 参数中提取 (如果是 Activity)
        var resolvedActivity: Activity? = null
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .forEach { ctx ->
                if (ctx is Activity) {
                    resolvedActivity = ctx
                    return@forEach
                }
            }

        // 3. 如果找到了 Activity，立即更新缓存并【刷新队列】
        if (resolvedActivity != null) {
            activityRef = WeakReference(resolvedActivity)
            // ★★★ 核心修复：一旦拿到 Activity，马上检查有没有积压的 Toast ★★★
            if (pendingQueue.isNotEmpty()) {
                // 放到主线程去刷新，防止并发问题
                Handler(Looper.getMainLooper()).post {
                    flushPending(resolvedActivity!!)
                }
            }
            return resolvedActivity
        }

        // 4. 注册生命周期作为最后一道防线
        if (!lifecycleRegistered) {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    activityRef = WeakReference(activity)
                    // Activity 恢复时，刷新队列
                    flushPending(activity)
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (activityRef?.get() == activity) {
                        removeNow()
                        activityRef = null
                    }
                }
            })
            lifecycleRegistered = true
        }
        return activityRef?.get()
    }

    private fun enqueuePending(msg: String, iconRes: Int, type: ToastType, duration: Long, context: Context) {
        synchronized(pendingQueue) {
            pendingQueue.add(PendingToast(msg, iconRes, type, duration))
        }
        resolveActivity(context)
    }

    private fun flushPending(activity: Activity) {
        val tasks = synchronized(pendingQueue) {
            val copy = pendingQueue.toList()
            pendingQueue.clear()
            copy
        }
        tasks.forEach { show(activity, it.msg, it.iconRes, it.type, it.duration) }
    }

    private fun dp2px(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}