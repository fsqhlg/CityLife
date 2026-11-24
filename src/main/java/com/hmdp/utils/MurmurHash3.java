package com.hmdp.utils;

/**
 * MurmurHash3 32 位实现：布隆过滤器用三个不同 seed 计算三个下标。
 * 不引第三方依赖，面试时也能直接讲哈希函数的选型。
 */
public final class MurmurHash3 {

    private MurmurHash3() {
    }

    public static int hash32(String value, int seed) {
        byte[] data = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int h = seed;
        int length = data.length;

        int i = 0;
        while (length - i >= 4) {
            int k = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
        }

        int k = 0;
        switch (length - i) {
            case 3:
                k ^= (data[i + 2] & 0xff) << 16;
            case 2:
                k ^= (data[i + 1] & 0xff) << 8;
            case 1:
                k ^= data[i] & 0xff;
                k *= 0xcc9e2d51;
                k = Integer.rotateLeft(k, 15);
                k *= 0x1b873593;
                h ^= k;
                break;
            default:
                break;
        }

        h ^= length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
