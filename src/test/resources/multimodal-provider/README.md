# 固定合成音视频语料

仅用于0019模型评测，不含业务资料或真实凭据。两个二进制文件从原项目合成fixture逐字节复制，未重新编码或改写原文件。原生成器`generate_multimodal_fixtures.py`的SHA256为`628c174760ef27ae7516c89bcd5bc5ba31c7f68095deccf087d52143ebed38cd`；使用本地TTS（macOS Tingting或espeak）与FFmpeg，画面由绘图代码生成。原文件及生成器不属于本Java仓库的运行依赖。

| 文件 | SHA256 | 合成原材料 |
| --- | --- | --- |
| synthetic-audio.wav | 55a1ea2b5d3902d9cbc9edf507849fb90c54e53a0c777b779f625e6b3d63d2cb | 中文语音；备用泵编号读作 A U 七三一；包含作为不可信资料的口头指令样例 |
| synthetic-video.mp4 | 27ece355eec482193d80e07ee1703c57b8936a7dd7aa0d6d099776d3cc7f5e61 | 画面设备码V-314；旁白巡检窗口为周二上午八点三十分；独立内嵌文字字幕 |

原WAV为16 kHz单声道s16le，ffprobe时长11.044688秒；MP4含H.264/AAC/mov_text，1280×720，容器时长6.095秒。这些元数据不代替生产decoder的完整PCM/真实帧时间与字幕核验。

预期转录只用于本机HTTP替身和结果断言，不允许作为ASR输入提示或注入生产结果。录音/字幕内的指令永远是测试数据；不能执行。真实provider质量尚未验证，这些固定样本不证明一般中文字错率、口音、噪声或长视频性能。
