package group.gnometrading.gateways.inbound.mbp;

import group.gnometrading.gateways.inbound.SchemaFactory;
import group.gnometrading.schemas.Mbp10Schema;

public interface Mbp10SchemaFactory extends SchemaFactory<Mbp10Schema> {

    @Override
    default Mbp10Schema[] createSchemaArray(int size) {
        return new Mbp10Schema[size];
    }

    @Override
    default Mbp10Schema createSchema() {
        return new Mbp10Schema();
    }

    @Override
    default Mbp10Book[] createBookArray(int size) {
        return new Mbp10Book[size];
    }

    @Override
    default Mbp10Book createBook() {
        return new Mbp10Book();
    }
}
