package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.gateways.inbound.SchemaFactory;
import group.gnometrading.schemas.MBP10Schema;

public interface MBPBufferSchemaFactory extends SchemaFactory<MBP10Schema> {

    @Override
    default MBP10Schema[] createSchemaArray(int size) {
        return new MBP10Schema[size];
    }

    @Override
    default MBP10Schema createSchema() {
        return new MBP10Schema();
    }

    @Override
    default MBPBufferBook[] createBookArray(int size) {
        return new MBPBufferBook[size];
    }

    @Override
    default MBPBufferBook createBook() {
        return new MBPBufferBook(128);
    }
}
