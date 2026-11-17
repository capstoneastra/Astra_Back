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
 * organizations: 기업(COMPANY) · 시험기관(INSTITUTION) · 대학(UNIVERSITY) · 플랫폼 운영(ADMIN) 조직.
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

    @Column(name = "has_secure_facility") private Boolean hasSecureFacility;
    @Column(name = "security_note", length = 1000) private String securityNote;

    public void updateSecurity(Boolean facility, String note) {
        if (facility != null) hasSecureFacility = facility;
        if (note != null) securityNote = note;
    }

    /** 가입으로 생긴 기업·신규 기관은 관리자가 회원 승인할 때 TRUE */
    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    /** 기업 회원가입: 관리자가 가입을 승인할 때 verified */
    public static Organization newCompany(String name, String businessRegNo) {
        Organization org = new Organization();
        org.orgType = OrgType.COMPANY;
        org.name = name;
        org.businessRegNo = businessRegNo;
        org.verified = false;
        return org;
    }

    /** 미등록 기관·대학 담당자 가입: 관리자 승인 전까지 is_verified=false */
    public static Organization newInstitutionApplication(OrgType orgType, String name, String address,
                                                         String phone) {
        Organization org = new Organization();
        org.orgType = requireInstitutionType(orgType);
        org.name = name;
        org.address = address;
        org.phone = phone;
        org.verified = false;
        return org;
    }

    /** 거절된 기업 신청 조직을 다시 신청할 때 (미승인 상태 유지) */
    public void reapplyCompany(String businessRegNo) {
        this.businessRegNo = businessRegNo;
    }

    /** 거절된 기관 신청 조직을 다시 신청할 때 (미승인 상태 유지) */
    public void reapplyInstitution(String address, String phone) {
        this.address = address;
        this.phone = phone;
    }

    /** 관리자가 기관·대학을 직접 등록 (바로 승인됨) */
    public static Organization newInstitutionByAdmin(OrgType orgType, String name, String parentName, String region,
                                                     String address, String phone, String email,
                                                     String homepageUrl) {
        Organization org = new Organization();
        org.orgType = requireInstitutionType(orgType);
        org.verified = true;
        org.name = name;
        org.parentName = parentName;
        org.region = region;
        org.address = address;
        org.phone = phone;
        org.email = email;
        org.homepageUrl = homepageUrl;
        return org;
    }

    /** 관리자 수정: null 이 아닌 값만 바꿈 */
    public void update(String name, String parentName, String businessRegNo, String region, String address,
                       String phone, String email, String homepageUrl) {
        if (name != null) this.name = name;
        if (parentName != null) this.parentName = parentName;
        if (businessRegNo != null) this.businessRegNo = businessRegNo;
        if (region != null) this.region = region;
        if (address != null) this.address = address;
        if (phone != null) this.phone = phone;
        if (email != null) this.email = email;
        if (homepageUrl != null) this.homepageUrl = homepageUrl;
    }

    /** 관리자 승인 */
    public void verify() {
        this.verified = true;
    }

    /** 시험기관 또는 대학 (가입 화면의 선택 목록에 나오는 조직) */
    public boolean isInstitution() {
        return orgType.isInstitutionType();
    }

    /** 장비를 보유할 수 있는 조직: 기업·시험기관·대학 */
    public boolean canOwnEquipment() {
        return orgType.canOwnEquipment();
    }

    private static OrgType requireInstitutionType(OrgType orgType) {
        if (orgType == null || !orgType.isInstitutionType()) {
            throw new IllegalArgumentException("기관 유형(INSTITUTION, UNIVERSITY)이 아닙니다: " + orgType);
        }
        return orgType;
    }
}
