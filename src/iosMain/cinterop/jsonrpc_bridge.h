#ifndef JSONRPC_BRIDGE_H
#define JSONRPC_BRIDGE_H

#include <stdint.h>

void *jsonrpc_client_connect(const char *options_json, char **error_out);
char *jsonrpc_client_call(void *client, const char *method, const char *params_json, char **error_out);
char *jsonrpc_client_take_liveness_log(void *client, char **error_out);
int32_t jsonrpc_client_notify(void *client, const char *method, const char *params_json, char **error_out);
int32_t jsonrpc_client_transport_ping(void *client, char **error_out);
int32_t jsonrpc_client_set_ping_frequency(void *client, uint64_t frequency_ms, char **error_out);
int32_t jsonrpc_client_set_max_missed_pongs(void *client, uint32_t count, char **error_out);
int32_t jsonrpc_client_set_request_timeout(void *client, uint64_t timeout_ms, char **error_out);
int32_t jsonrpc_client_disconnect(void *client, char **error_out);
void jsonrpc_client_release(void *client);
void jsonrpc_string_free(char *value);

#endif
