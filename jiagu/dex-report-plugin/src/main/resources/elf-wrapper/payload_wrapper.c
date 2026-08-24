typedef __SIZE_TYPE__ size_t;
typedef unsigned char uint8_t;

extern const uint8_t jiagu_payload_start[];
extern const uint8_t jiagu_payload_end[];

__attribute__((visibility("default"), used))
const uint8_t *jg_payload_address(void) {
    return jiagu_payload_start;
}

__attribute__((visibility("default"), used))
size_t jg_payload_size(void) {
    return (size_t) (jiagu_payload_end - jiagu_payload_start);
}
