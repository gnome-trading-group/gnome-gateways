package group.gnometrading.gateways.fix;

public enum FIXVersion {

    FIX_4_2("FIX.4.2", 4),
    FIX_4_3("FIX.4.3", 5),
    FIX_4_4("FIX.4.4", 6),
    FIXT_1_1("FIXT.1.1", -1),
    FIX_5_0SP2("FIX.5.0SP2", 9);

    private final String beginString;
    private final byte[] beginStringBytes;
    private final int applicationVersionID;

    FIXVersion(String beginString, int applicationVersionID) {
        this.beginString = beginString;
        this.beginStringBytes = this.beginString.getBytes();
        this.applicationVersionID = applicationVersionID;
    }

    public String getBeginString() {
        return beginString;
    }

    public byte[] getBeginStringBytes() {
        return beginStringBytes;
    }

    public int getApplicationVersionID() {
        return applicationVersionID;
    }

}
