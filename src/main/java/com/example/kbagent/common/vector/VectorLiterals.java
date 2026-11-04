package com.example.kbagent.common.vector;

/** pgvector 的文本格式 [0.1,0.2,...]。写入和检索时都以字符串参数配合 CAST(? AS vector) 传给数据库。 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
