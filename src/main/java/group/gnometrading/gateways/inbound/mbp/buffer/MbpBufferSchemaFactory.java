package group.gnometrading.gateways.inbound.mbp.buffer;

import group.gnometrading.gateways.inbound.SchemaFactory;
import group.gnometrading.schemas.Mbp10Schema;

public interface MbpBufferSchemaFactory extends SchemaFactory<Mbp10Schema> {

    @Override
    default Mbp10Schema[] createSchemaArray(int size) {
        return new Mbp10Schema[size];
    }

    @Override
    default Mbp10Schema createSchema() {
        return new Mbp10Schema();
    }

    @Override
    default MbpBufferBook[] createBookArray(int size) {
        return new MbpBufferBook[size];
    }

    @Override
    default MbpBufferBook createBook() {
        return new MbpBufferBook(128);
    }
}
