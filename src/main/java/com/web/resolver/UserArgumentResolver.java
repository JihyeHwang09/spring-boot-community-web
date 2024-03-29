package com.web.resolver;

import com.web.annotation.SocialUser;
import com.web.domain.User;
import com.web.domain.enums.SocialType;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static com.web.domain.enums.SocialType.FACEBOOK;
import static com.web.domain.enums.SocialType.GOOGLE;
import static com.web.domain.enums.SocialType.KAKAO;

@Component
public class UserArgumentResolver implements HandlerMethodArgumentResolver {

/*
    MethodParameter로 해당 파라미터의 정보를 받게 된다.
    파라미터에 @SocialUser 어노테이션이 있고, 타입이 User인 파라미터만 true를 반환할 것이다.
    supportsParameter() 메서드에서 처음 한 번 체크된 부분은 캐시되어 이후의 동일한 호출 시에는
     체크되지 않고 캐시된 결과값을 바로 반환한다.
 */


    /*
    resolveArgument() 메서드는 검증이 완료된 파라미터 정보를 받는다.
    이미 검증이 되어 세션에 해당 User 객체가 있으면,
    User 객체를 구성하는 로직을 수행하지 않도록 세션을 먼저 확인하는 코드를 구현하자.
    세션은 RequestContextHolder를 사용해서 가져올 수 있다.
     */
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterAnnotation(SocialUser.class)
                != null && parameter.getParameterType().equals(User.class);
    }

    /*
    세션에서 인증된 User 객체를 가져온다.
    getUser() 메서드를 만들어 세션에서 가져온 User 객체가 없으면 새로 생성하고,
    이미 있다면 바로 사용하도록 반환한다.
    getUser() 메서드는 인증된 User 객체를 만들어 권한까지 부여하는 코드이기 때문에 현재 코드에서 제외시켰다.
    각 소셜 미디어마다 다른 네이밍 방식을 취하고 있기 때문에 코드가 좀 더 길어질 수 있다.
 */
    public Object resolveArgument (MethodParameter parameter,
                            ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory) throws Exception {
        HttpSession session = ((ServletRequestAttributes) RequestContextHolder
        .currentRequestAttributes()).getRequest().getSession();
        User user = (User) session.getAttribute("user");
        return getUser(user, session);
    }

//  getUser() 메서드: User 객체를 만드는 메인 메서드
    private User getUser(User user, HttpSession session) {
        // 세션에서 가져온 user 객체가 null일 경우에만 로직이 수행된다.
        if (user == null) {
            try {
                // SecurityContextHolder를 사용해 인증된 OAuth2Authentication 객체를 가져온다.
                OAuth2Authentication authentication = (OAuth2Authentication)
                        SecurityContextHolder.getContext().getAuthentication();

                // 불러온 OAuth2Authentication 객체에서 getDetails() 메서드를 사용해
                // 사용자 개인정보를 Map 타입으로 매핑한다.
                @SuppressWarnings("unchecked")
                Map<String, String> map = (HashMap<String, String>)
                        authentication.getUserAuthentication().getDetails();

                // converUser() 메서드의 로직
                // : socialType. 즉, 어떤 소셜 미디어로 인증을 받았는지
                // String.valueOf(authentication.getAuthorities().toArray()[0])로 불러온다.
                // 이전에 넣어주었던 권한이 하나 뿐이라서 배열의 첫 번째 값만 불러오도록 작성했다.
                User convertUser = convertUser (String.valueOf(authentication
                .getAuthorities().toArray()[0]), map);

                // 여기서는 소셜에서 항상 이메일 정보를 제공한다는 조건하에 작성한다.
                user = userRepository.findByEmail(convertUser.getEmail());
                // 이메일을 사용해 이미 DB에 저장된 사용자라면
                if (user == null) {
                // -> 바로 User 객체를 반환하고,
                    user = userRepository.save(convertUser);
                }
                // 저장되지 않은 사용자라면,
                // User 테이블에 저장한다.
                setRoleIfNotSame(user, authentication, map);
                session.setAttribute("user", user);
            } catch (ClassCastException) {
                return user;
            }
        }
        return  user;
    }

    /*
    convertUser() 메서드
    : 사용자의 인증된 소셜 미디어 타입에 따라 빌더를 사용하여
    User 객체를 만들어 주는 가교 역할을 한다.
    카카오의 경우에는 별도의 메서드를 사용한다.
    */
    private User convertUser(String authority, Map<String, String> map) {
        if (FACEBOOK.isEquals(authority)) return  getModernUser(FACEBOOK, map);
        else if (GOOGLE.isEquals(authority)) return getModernUser(GOOGLE, map);
        else if (KAKAO.isEquals(authority)) return  getKaKaoUser(map);
        return null;
    }


    /*
    getModernUser() 메서드
    : 페이스북이나 구글과 같이 공통되는 명명규칙을 가진 그룹을
    User 객체로 매핑해준다.
     */
    private User getModernUser(SocialType socialType, Map<String, String> map) {
        return User.builder()
                .name(map.get("name"))
                .email(map.get("email"))
                .principal(map.get("id"))
                .socialType(socialType)
                .createdDate(LocalDateTime.now())
                .build();
    }

    /*
    getKakaoUser() 메서드
    : (키의 네이밍값이 타 소셜 미디어와 다른) 카카오 회원을 위한 메서드
    getModernuser() 메서드와 동일하게 User 객체로 매핑해준다.
     */
    private User getKaKaoUser(Map<String, String> map) {
        HashMap<String, String> propertyMap = (HashMap<String, String>)(Object)
                map.get("properties");
        return User.builder()
                .name(propertyMap.get("nickname"))
                .email(map.get("kaccount_email"))
                .principal(String.valueOf(map.get("id")))
                .socialType(KAKAO)
                .createdDate(LocalDateTime.now())
                .build();
    }

    /*
    setRoleInfNotSame() 메서드
    : 인증된 authentication이 권한을 갖고 있는지 체크하는 용도로 사용
    만약, 저장된 User 권한이 없으면 SecurityContextHolder를 사용하여 해당 소셜 미디어 타입으로
    권한을 저장한다.
     */
    private void setRoleIfNotSame(User user, OAuth2Authentication authentication,
                                  Map<String, String> map) {
        if (!authentication.getAuthorities().contains(new
                SimpleGrantedAuthority(user.getSocialType().getRoleType()))) {
            SecurityContextHolder.getContext().setAuthentication(new
                    UsernamePasswordAuthenticationToken(map, "N/A",
                    AuthorityUtils.createAuthorityList(user.getSocialType()
                    .getRoleType())));
        }
    }
}
