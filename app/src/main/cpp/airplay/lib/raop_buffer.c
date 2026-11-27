/**
 *  Copyright (C) 2011-2012  Juho Vähä-Herttua
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 */
#ifdef WIN32
#include <stdio.h>
#endif // WIN32

#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <math.h>

#include "raop_buffer.h"
#include "raop_rtp.h"

#include <stdint.h>
#include <sha512.h>
#include "crypto/crypto.h"
#include "aes.h"
#include "compat.h"
#include "stream.h"

#include "aac_decoder.h"

/* Zeroed tail kept after compressed input (was FFmpeg's padding requirement). */
#define AAC_INPUT_PADDING 64


#define RAOP_BUFFER_LENGTH 64  // Reduced from 512 to minimize latency (5.12s -> 640ms max buffer)
#define RAOP_AUDIO_MAX_CHANNELS 2
#define RAOP_AUDIO_MAX_SAMPLES_PER_FRAME 2048

typedef aac_decoder_t raop_aac_decoder_t;

typedef struct {
	/* Packet available */
	int available;

	/* RTP header */
	unsigned char flags;
	unsigned char type;
	unsigned short seqnum;
	unsigned int timestamp;
	unsigned int ssrc;

	uint32_t sample_rate;
	uint16_t channels;
	uint16_t bits_per_sample;

	/* memory size */
	int audio_buffer_size;
	/* decoded length */
	int audio_buffer_len;
	void *audio_buffer;
} raop_buffer_entry_t;

struct raop_buffer_s {
    logger_t *logger;
	/* key and IV for decryption */
	unsigned char aeskey[RAOP_AESKEY_LEN];
	unsigned char aesiv[RAOP_AESIV_LEN];

	raop_aac_decoder_t *decoder;

	/* First and last seqnum */
	int is_empty;
	// playback sequence number
	unsigned short first_seqnum;
	// received sequence number
	unsigned short last_seqnum;

	/* RTP buffer entries */
	raop_buffer_entry_t entries[RAOP_BUFFER_LENGTH];

	/* Buffer of all audio buffers */
	int buffer_size;
	void *buffer;
};

/* period size 480 samples */
#define N_SAMPLE 480

void
raop_buffer_init_key_iv(raop_buffer_t *raop_buffer,
                     const unsigned char *aeskey,
                     const unsigned char *aesiv,
                     const unsigned char *ecdh_secret)
{
    // initialize key
    unsigned char eaeskey[64];
    memcpy(eaeskey, aeskey, 16);
    sha512_context ctx;
    sha512_init(&ctx);
    sha512_update(&ctx, eaeskey, 16);
    sha512_update(&ctx, ecdh_secret, 32);
    sha512_final(&ctx, eaeskey);
    memcpy(raop_buffer->aeskey, eaeskey, 16);
    memcpy(raop_buffer->aesiv, aesiv, RAOP_AESIV_LEN);
#ifdef DUMP_AUDIO
    if (file_keyiv != NULL) {
        fwrite(raop_buffer->aeskey, 16, 1, file_keyiv);
        fwrite(raop_buffer->aesiv, 16, 1, file_keyiv);
        fclose(file_keyiv);
    }
#endif
}

raop_buffer_t *
raop_buffer_init(logger_t *logger,
                 const unsigned char *aeskey,
                 const unsigned char *aesiv,
                 const unsigned char *ecdh_secret)
{
	raop_buffer_t *raop_buffer;
	int audio_buffer_size;
	assert(aeskey);
    assert(aesiv);
    assert(ecdh_secret);
	raop_buffer = calloc(1, sizeof(raop_buffer_t));
	if (!raop_buffer) {
		return NULL;
	}
    raop_buffer->logger = logger;

	/* Allocate bounded interleaved signed 16-bit PCM output buffers. */
	audio_buffer_size = RAOP_AUDIO_MAX_SAMPLES_PER_FRAME
		* RAOP_AUDIO_MAX_CHANNELS * sizeof(short);
	raop_buffer->decoder = aac_decoder_create(logger);
	if (!raop_buffer->decoder) {
        free(raop_buffer);
        return NULL;
    }
	raop_buffer->buffer_size = audio_buffer_size * RAOP_BUFFER_LENGTH;
	raop_buffer->buffer = malloc(raop_buffer->buffer_size);
	if (!raop_buffer->buffer) {
		aac_decoder_destroy(raop_buffer->decoder);
		free(raop_buffer);
		return NULL;
	}
	for (int i=0; i<RAOP_BUFFER_LENGTH; i++) {
		raop_buffer_entry_t *entry = &raop_buffer->entries[i];
		entry->audio_buffer_size = audio_buffer_size;
		entry->audio_buffer_len = 0;
		entry->audio_buffer = (char *)raop_buffer->buffer+i*audio_buffer_size;
	}
    raop_buffer_init_key_iv(raop_buffer, aeskey, aesiv, ecdh_secret);
	/* Mark buffer as empty */
	raop_buffer->is_empty = 1;

	return raop_buffer;
}

void
raop_buffer_destroy(raop_buffer_t *raop_buffer)
{
	if (raop_buffer) {
		aac_decoder_destroy(raop_buffer->decoder);
		free(raop_buffer->buffer);
		free(raop_buffer);
	}
#ifdef DUMP_AUDIO
    if (file_aac != NULL) {
        fclose(file_aac);
    }
    if (file_source != NULL) {
        fclose(file_source);
    }
    if (file_pcm != NULL) {
        fclose(file_pcm);
    }
#endif

}

static short
seqnum_cmp(unsigned short s1, unsigned short s2)
{
	return (s1 - s2);
}

short dithered_vol(int sample, int v) {
    int out = sample * v;
/*    if (v < 65536) {
        out = (out + rand_a) - rand_b;
    }*/
    return (short) (out >> 16);
}

int
stuff_buffer(short* input, short* output, int vol) {
    int i;
    int i2;
    int l;
    int stuffsamp = 480;
    int l2 = 0;
    int j = 0;
    for (i = 0; i < stuffsamp; i++) {
        i2 = j + 1;
        l = l2 + 1;
        output[j] = dithered_vol(input[l2], vol);
        j = i2 + 1;
        l2 = l + 1;
        output[i2] = dithered_vol(input[l], vol);
    }
    return 480;
}

//#define DUMP_AUDIO

#ifdef DUMP_AUDIO
static FILE* file_aac = NULL;
static FILE* file_source = NULL;
static FILE* file_keyiv = NULL;
static FILE* file_pcm = NULL;
#endif


int
raop_buffer_queue(raop_buffer_t *raop_buffer, unsigned char *data, unsigned short datalen, raop_callbacks_t *callbacks)
{
    assert(raop_buffer);
    int encryptedlen;
    raop_buffer_entry_t *entry;
#ifdef DUMP_AUDIO
    if (file_aac == NULL) {
        file_aac = fopen("demo-audio.aac", "wb");
        file_source = fopen("demo-audio.source", "wb");
        file_keyiv = fopen("demo-audio.keyiv", "wb");
        file_pcm = fopen("demo-audio.pcm", "wb");
    }
#endif

    /* Check packet data length is valid */
    if (datalen < 12 || datalen > RAOP_PACKET_LEN) {
        return -1;
    }
    unsigned short seqnum = (data[2] << 8) | data[3];
    if (datalen == 16 && data[12] == 0x0 && data[13] == 0x68 && data[14] == 0x34 && data[15] == 0x0) {
        return 0;
    }
    int payloadsize = datalen - 12;
#ifdef DUMP_AUDIO
    // unencrypted file
    if (file_source != NULL) {
        fwrite(&data[12], payloadsize, 1, file_source);
    }
#endif
    //logger_log(raop_buffer->logger, LOGGER_DEBUG, "seqnum = %d payloadsize = %d", seqnum, payloadsize);


	if (!raop_buffer->is_empty && seqnum_cmp(seqnum, raop_buffer->first_seqnum) < 0) {
		return 0;
	}
	/* Check that there is always space in the buffer, otherwise flush */
	if (seqnum_cmp(seqnum, raop_buffer->first_seqnum+RAOP_BUFFER_LENGTH) >= 0) {
		raop_buffer_flush(raop_buffer, seqnum);
	}
	entry = &raop_buffer->entries[seqnum % RAOP_BUFFER_LENGTH];
	if (entry->available && seqnum_cmp(entry->seqnum, seqnum) == 0) {
		/* Packet resend, we can safely ignore */
		return 0;
	}
    entry->flags = data[0];
    entry->type = data[1];
    entry->seqnum = seqnum;
    // pts starts from byte 4
    entry->timestamp = (data[4] << 24) | (data[5] << 16) |
                       (data[6] << 8) | data[7];
    entry->ssrc = (data[8] << 24) | (data[9] << 16) |
                  (data[10] << 8) | data[11];
    entry->available = 1;
	entry->audio_buffer_len = 0;
	entry->sample_rate = 0;
	entry->channels = 0;
	entry->bits_per_sample = 0;

    encryptedlen = payloadsize/16*16;
	/* Keep a zeroed tail after the compressed input. */
	unsigned char* packetbuf = malloc(payloadsize + AAC_INPUT_PADDING);
	if (!packetbuf) {
		entry->available = 0;
		return -1;
	}
	memset(packetbuf, 0, payloadsize + AAC_INPUT_PADDING);
	// needs to be initialized internally
    AES_CTX aes_ctx_audio;
	AES_set_key(&aes_ctx_audio, raop_buffer->aeskey, raop_buffer->aesiv, AES_MODE_128);
	AES_convert_key(&aes_ctx_audio);
    AES_cbc_decrypt(&aes_ctx_audio, &data[12], packetbuf, encryptedlen);
    memcpy(packetbuf+encryptedlen, &data[12+encryptedlen], payloadsize-encryptedlen);
#ifdef DUMP_AUDIO
    // decrypted file
    if (file_aac != NULL) {
        fwrite(packetbuf, payloadsize, 1, file_aac);
    }
#endif
	/* Decode one decrypted raw AAC-ELD access unit to interleaved S16 PCM. */
	int ret = aac_decoder_decode(raop_buffer->decoder, raop_buffer->logger,
		packetbuf, payloadsize, entry->audio_buffer, entry->audio_buffer_size,
		&entry->audio_buffer_len, &entry->sample_rate, &entry->channels,
		&entry->bits_per_sample);
	if (ret <= 0) {
		/* Preserve RTP sequence progression, but never expose stale or
		 * uninitialized PCM after an AAC decode failure or delay. */
		entry->available = 0;
	}
#ifdef DUMP_AUDIO
    if (file_pcm != NULL) {
        fwrite(entry->audio_buffer, entry->audio_buffer_len, 1, file_pcm);
    }
#endif

	/* Update the raop_buffer seqnums */
	if (raop_buffer->is_empty) {
		raop_buffer->first_seqnum = seqnum;
		raop_buffer->last_seqnum = seqnum;
		raop_buffer->is_empty = 0;
	}
	if (seqnum_cmp(seqnum, raop_buffer->last_seqnum) > 0) {
		raop_buffer->last_seqnum = seqnum;
	}
	free(packetbuf);

    return 1;
}

const void *
raop_buffer_dequeue(raop_buffer_t *raop_buffer, int *length, unsigned int* pts, int no_resend,
	uint32_t* sample_rate, uint16_t* channels, uint16_t* bits_per_sample)
{
	short buflen;
	raop_buffer_entry_t *entry;

	/* Calculate number of entries in the current buffer */
	buflen = seqnum_cmp(raop_buffer->last_seqnum, raop_buffer->first_seqnum) + 1;

	/* Cannot dequeue from empty buffer */
	if (raop_buffer->is_empty || buflen <= 0) {
		return NULL;
	}

	/* Get the first buffer entry for inspection */
	entry = &raop_buffer->entries[raop_buffer->first_seqnum % RAOP_BUFFER_LENGTH];
	if (no_resend) {
		/* If we do no resends, always return the first entry */
	} else if (!entry->available) {
		/* Check how much we have space left in the buffer */
		if (buflen < 4) {  // Reduced from RAOP_BUFFER_LENGTH to start playback faster (~40ms vs 640ms)
			/* Return nothing and hope resend gets on time */
			return NULL;
		}
		/* Risk of buffer overrun, return empty buffer */
	}

	/* Update buffer and validate entry */
	raop_buffer->first_seqnum += 1;
	if (!entry->available) {
		/* Advance over gaps and failed decodes without synthesizing PCM with
		 * unknown format metadata. The caller skips this zero-length entry. */
		*length = 0;
		*pts = 0;
		*sample_rate = 0;
		*channels = 0;
		*bits_per_sample = 0;
		return entry->audio_buffer;
	}
	entry->available = 0;

	/* Return entry audio buffer */
	*length = entry->audio_buffer_len;
	*pts = entry->timestamp;
	*sample_rate = entry->sample_rate;
	*channels = entry->channels;
	*bits_per_sample = entry->bits_per_sample;

	entry->audio_buffer_len = 0;
	return entry->audio_buffer;
}

void
raop_buffer_handle_resends(raop_buffer_t *raop_buffer, raop_resend_cb_t resend_cb, void *opaque)
{
	raop_buffer_entry_t *entry;

	assert(raop_buffer);
	assert(resend_cb);

	if (seqnum_cmp(raop_buffer->first_seqnum, raop_buffer->last_seqnum) < 0) {
		int seqnum, count;

		for (seqnum=raop_buffer->first_seqnum; seqnum_cmp(seqnum, raop_buffer->last_seqnum)<0; seqnum++) {
			entry = &raop_buffer->entries[seqnum % RAOP_BUFFER_LENGTH];
			if (entry->available) {
				break;
			}
		}
		if (seqnum_cmp(seqnum, raop_buffer->first_seqnum) == 0) {
			return;
		}
		count = seqnum_cmp(seqnum, raop_buffer->first_seqnum);
		resend_cb(opaque, raop_buffer->first_seqnum, count);
	}
}

void
raop_buffer_flush(raop_buffer_t *raop_buffer, int next_seq)
{
	int i;
	assert(raop_buffer);
	for (i=0; i<RAOP_BUFFER_LENGTH; i++) {
		raop_buffer->entries[i].available = 0;
		raop_buffer->entries[i].audio_buffer_len = 0;
	}
	if (raop_buffer->decoder) {
		aac_decoder_flush(raop_buffer->decoder);
	}
	if (next_seq < 0 || next_seq > 0xffff) {
		raop_buffer->is_empty = 1;
	} else {
		raop_buffer->first_seqnum = next_seq;
		raop_buffer->last_seqnum = next_seq-1;
	}
}
