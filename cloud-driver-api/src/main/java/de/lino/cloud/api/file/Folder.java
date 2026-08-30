package de.lino.cloud.api.file;

import com.google.common.collect.Lists;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter @ToString
@EqualsAndHashCode(callSuper = true)
public final class Folder extends Serialized {

    private final List<String> storedFileIds;

    public Folder() {
        this.storedFileIds = Lists.newCopyOnWriteArrayList();
    }

    @Override
    public List<String> keysOf() {
        return List.of();
    }

}
