package com.example.ssds.infra.entity.id;

import java.io.Serializable;
import lombok.*;

/** climate_normal 的複合主鍵（region_code, month）。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ClimateNormalId implements Serializable {

    private String regionCode;
    private Short month;
}
