/*
 * PIN pairing (pair-setup-pin with SRP + AES-GCM) is not supported in the
 * Android TV port. The receiver never sets a password, so raop.c never takes
 * the PIN path; these stubs exist only to satisfy the linker and fail closed.
 */
#include <stddef.h>
#include "pinpair.h"

pin_pairing_t *
pin_pairing_create(const char *username, const char *pin,
	uint8_t salt[PINPAIR_SALT_SIZE], uint8_t public_key[PINPAIR_PUBLIC_KEY_SIZE])
{
	(void)username; (void)pin; (void)salt; (void)public_key;
	return NULL;
}

int
pin_pairing_verify(pin_pairing_t *pairing, const uint8_t *client_public_key,
	int client_public_key_len, const uint8_t *client_proof, int client_proof_len,
	uint8_t server_proof[PINPAIR_PROOF_SIZE])
{
	(void)pairing; (void)client_public_key; (void)client_public_key_len;
	(void)client_proof; (void)client_proof_len; (void)server_proof;
	return -1;
}

int
pin_pairing_confirm(pin_pairing_t *pairing,
	const uint8_t client_epk[PINPAIR_ED25519_KEY_SIZE],
	const uint8_t client_auth_tag[PINPAIR_AUTH_TAG_SIZE],
	const uint8_t server_public_key[PINPAIR_ED25519_KEY_SIZE],
	uint8_t server_epk[PINPAIR_ED25519_KEY_SIZE],
	uint8_t server_auth_tag[PINPAIR_AUTH_TAG_SIZE],
	uint8_t client_public_key[PINPAIR_ED25519_KEY_SIZE])
{
	(void)pairing; (void)client_epk; (void)client_auth_tag; (void)server_public_key;
	(void)server_epk; (void)server_auth_tag; (void)client_public_key;
	return -1;
}

void
pin_pairing_destroy(pin_pairing_t *pairing)
{
	(void)pairing;
}
