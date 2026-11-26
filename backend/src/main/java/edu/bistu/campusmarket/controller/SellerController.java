package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.SellerDtos.Application;
import edu.bistu.campusmarket.dto.SellerDtos.SellerView;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.SellerService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
@RequestMapping("/api/sellers")
public class SellerController {
    private final SellerService service;

    public SellerController(SellerService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SellerView>> me(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.find(user.userId())));
    }

    @PostMapping(path = "/personal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SellerView>> createPersonal(@Valid @RequestBody Application input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return created(service.createPersonal(user.userId(), input));
    }

    @PostMapping(path = "/business", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SellerView>> applyBusiness(@Valid @RequestPart("application") Application input,
            @RequestPart("businessLicense") MultipartFile license, @RequestPart("identityDocument") MultipartFile identity,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return created(service.applyBusiness(user.userId(), input, license, identity));
    }

    private ResponseEntity<ApiResponse<SellerView>> created(SellerView seller) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(seller));
    }
}
