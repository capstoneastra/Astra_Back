package kr.astra.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.astra.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * organizations: 기업(COMPANY) · 시험기관(INSTITUTION) · 플랫폼 운영(ADMIN) 조직.
 * 같은 유형 안에서 이름이 겹칠 수 없음 (UNIQUE(org_type, name)).
 */
@Getter
@Entity
@Table(name = "organizations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "org_type", nullable = false, length = 20)
    private OrgType orgType;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "parent_name", length = 200)
    private String parentName;

    @Column(name = "business_reg_no", length = 20)
    private String businessRegNo;

    @Column(length = 50)
    private String region;

    @Column(length = 300)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(name = "homepage_url", length = 500)
    private String homepageUrl;

    /** 신규 기관 신청은 관리자가 승인해야 TRUE */
    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    /** 기업 회원가입: 기업 조직은 따로 승인 절차가 없어 바로 verified */
    public static Organization newCompany(String name, String businessRegNo) {
        Organization org = new Organization();
        org.orgType = OrgType.COMPANY;
        org.name = name;
        org.businessRegNo = businessRegNo;
        org.verified = true;
        return org;
    }

    /** 미등록 기관 담당자 가입: 관리자 승인 전까지 is_verified=false */
    public static Organization newInstitutionApplication(String name, String address, String phone) {
        Organization org = new Organization();
        org.orgType = OrgType.INSTITUTION;
        org.name = name;
        org.address = address;
        org.phone = phone;
        org.verified = false;
        return org;
    }

    public boolean isInstitution() {
        return orgType == OrgType.INSTITUTION;
    }
}
