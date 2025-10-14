package group.gnometrading.gateways.inbound.mbp;

import group.gnometrading.gateways.inbound.SchemaFactory;
import group.gnometrading.schemas.MBP10Schema;

public interface MBP10SchemaFactory extends SchemaFactory<MBP10Schema> {

    @Override
    default MBP10Schema[] createSchemaArray(int size) {
        return new MBP10Schema[size];
    }

    @Override
    default MBP10Schema createSchema() {
        return new MBP10Schema();
    }

    @Override
    default MBP10Book[] createBookArray(int size) {
        return new MBP10Book[size];
    }

    @Override
    default MBP10Book createBook() {
        return new MBP10Book();
    }

}
