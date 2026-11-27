#include <stdlib.h>
#include <string.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include "aac_decoder.h"

#define AAC_MIME "audio/mp4a-latm"
#define AAC_INPUT_TIMEOUT_US 10000
#define AAC_FIRST_OUTPUT_TIMEOUT_US 4000

struct aac_decoder_s {
	AMediaCodec *codec;
	int32_t sample_rate;
	int32_t channels;
	int64_t pts_us;
};

aac_decoder_t *
aac_decoder_create(logger_t *logger)
{
	/* AAC-ELD, 44.1 kHz, stereo, 480 samples/frame: the AudioSpecificConfig
	 * used by AirPlay mirroring audio. */
	static const unsigned char eld_config[] = {0xF8, 0xE8, 0x50, 0x00};
	aac_decoder_t *decoder;
	AMediaFormat *format;
	media_status_t status;

	decoder = calloc(1, sizeof(aac_decoder_t));
	if (!decoder) {
		return NULL;
	}
	decoder->sample_rate = 44100;
	decoder->channels = 2;

	decoder->codec = AMediaCodec_createDecoderByType(AAC_MIME);
	if (!decoder->codec) {
		logger_log(logger, LOGGER_ERR, "MediaCodec AAC decoder unavailable");
		free(decoder);
		return NULL;
	}

	format = AMediaFormat_new();
	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, AAC_MIME);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 44100);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_AAC_PROFILE, 39); /* AOT_ER_AAC_ELD */
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_IS_ADTS, 0);
	AMediaFormat_setBuffer(format, "csd-0", (void *)eld_config, sizeof(eld_config));

	status = AMediaCodec_configure(decoder->codec, format, NULL, NULL, 0);
	AMediaFormat_delete(format);
	if (status != AMEDIA_OK) {
		logger_log(logger, LOGGER_ERR, "MediaCodec AAC-ELD configure failed (%d)", status);
		AMediaCodec_delete(decoder->codec);
		free(decoder);
		return NULL;
	}
	status = AMediaCodec_start(decoder->codec);
	if (status != AMEDIA_OK) {
		logger_log(logger, LOGGER_ERR, "MediaCodec AAC-ELD start failed (%d)", status);
		AMediaCodec_delete(decoder->codec);
		free(decoder);
		return NULL;
	}
	logger_log(logger, LOGGER_DEBUG, "MediaCodec AAC-ELD decoder ready");
	return decoder;
}

void
aac_decoder_destroy(aac_decoder_t *decoder)
{
	if (!decoder) {
		return;
	}
	if (decoder->codec) {
		AMediaCodec_stop(decoder->codec);
		AMediaCodec_delete(decoder->codec);
	}
	free(decoder);
}

void
aac_decoder_flush(aac_decoder_t *decoder)
{
	if (decoder && decoder->codec) {
		AMediaCodec_flush(decoder->codec);
	}
}

static void
read_output_format(aac_decoder_t *decoder)
{
	AMediaFormat *format = AMediaCodec_getOutputFormat(decoder->codec);
	int32_t value;
	if (!format) {
		return;
	}
	if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &value) && value > 0) {
		decoder->sample_rate = value;
	}
	if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &value) && value > 0) {
		decoder->channels = value;
	}
	AMediaFormat_delete(format);
}

int
aac_decoder_decode(aac_decoder_t *decoder, logger_t *logger,
	const unsigned char *packet_data, int packet_size, void *output,
	int output_capacity, int *output_length, uint32_t *sample_rate,
	uint16_t *channels, uint16_t *bits_per_sample)
{
	ssize_t index;
	size_t capacity = 0;
	uint8_t *buffer;
	int written = 0;
	int64_t timeout = AAC_FIRST_OUTPUT_TIMEOUT_US;

	if (!decoder || !decoder->codec || packet_size <= 0) {
		return -1;
	}

	index = AMediaCodec_dequeueInputBuffer(decoder->codec, AAC_INPUT_TIMEOUT_US);
	if (index < 0) {
		logger_log(logger, LOGGER_DEBUG, "AAC decoder input queue full; dropping packet");
		return -1;
	}
	buffer = AMediaCodec_getInputBuffer(decoder->codec, (size_t)index, &capacity);
	if (!buffer || capacity < (size_t)packet_size) {
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)index, 0, 0, 0, 0);
		return -1;
	}
	memcpy(buffer, packet_data, (size_t)packet_size);
	AMediaCodec_queueInputBuffer(decoder->codec, (size_t)index, 0, (size_t)packet_size,
		(uint64_t)decoder->pts_us, 0);
	decoder->pts_us += 480LL * 1000000LL / 44100LL;

	/* Drain whatever PCM is ready. Wait briefly for the first buffer only, so
	 * the network thread is never held up for long. */
	for (;;) {
		AMediaCodecBufferInfo info;
		index = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, timeout);
		if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
			read_output_format(decoder);
			continue;
		}
		if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
			continue;
		}
		if (index < 0) {
			break; /* AMEDIACODEC_INFO_TRY_AGAIN_LATER or error */
		}
		buffer = AMediaCodec_getOutputBuffer(decoder->codec, (size_t)index, &capacity);
		if (buffer && info.size > 0) {
			int size = info.size;
			if (size > output_capacity - written) {
				size = output_capacity - written;
			}
			size -= size % (decoder->channels * 2);
			if (size > 0) {
				memcpy((uint8_t *)output + written, buffer + info.offset, (size_t)size);
				written += size;
			}
		}
		AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)index, false);
		timeout = 0;
		if (written >= output_capacity) {
			break;
		}
	}

	if (written == 0) {
		return 0;
	}
	*output_length = written;
	*sample_rate = (uint32_t)decoder->sample_rate;
	*channels = (uint16_t)decoder->channels;
	*bits_per_sample = 16;
	return 1;
}
