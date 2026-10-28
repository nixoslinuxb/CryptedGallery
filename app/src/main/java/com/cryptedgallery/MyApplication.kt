package com.cryptedgallery

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder

class MyApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build()
    }
}
