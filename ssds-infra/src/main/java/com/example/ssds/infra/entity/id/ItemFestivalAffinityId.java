package com.example.ssds.infra.entity.id;

import java.io.Serializable;
import lombok.*;

/** item_festival_affinity 的複合主鍵（product_id, festival_code）。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ItemFestivalAffinityId implements Serializable {

    private Long product;
    private String festivalCode;
}
