/*
 * AAC-ELD decoder for AirPlay audio packets, backed by Android's MediaCodec
 * (NDK). Replaces the FFmpeg decoder used by the Windows build.
 */
#ifndef AAC_DECODER_H
#define AAC_DECODER_H

#include <stdint.h>
#include "logger.h"

typedef struct aac_decoder_s aac_decoder_t;

aac_decoder_t *aac_decoder_create(logger_t *logger);
void aac_decoder_destroy(aac_decoder_t *decoder);
void aac_decoder_flush(aac_decoder_t *decoder);

/* Decodes one raw AAC-ELD access unit into interleaved signed 16-bit PCM.
 * Returns 1 when PCM was written, 0 when the decoder has no output yet,
 * and -1 on error. */
int aac_decoder_decode(aac_decoder_t *decoder, logger_t *logger,
	const unsigned char *packet_data, int packet_size, void *output,
	int output_capacity, int *output_length, uint32_t *sample_rate,
	uint16_t *channels, uint16_t *bits_per_sample);

#endif
