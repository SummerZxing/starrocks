// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.pseudocluster;

import com.google.gson.annotations.SerializedName;

public class Rowset {
    @SerializedName(value = "id")
    int id = -1;
    @SerializedName(value = "rowsetid")
    String rowsetid;
    @SerializedName(value = "txnId")
    long txnId;
    @SerializedName(value = "numRows")
    long numRows = 0;
    @SerializedName(value = "dataSize")
    long dataSize = 0;

    Rowset(long txnId, String rowsetid) {
        this.txnId = txnId;
        this.rowsetid = rowsetid;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Rowset copy() {
        Rowset r = new Rowset(txnId, rowsetid);
        r.id = id;
        r.numRows = numRows;
        r.dataSize = dataSize;
        return r;
    }
}
