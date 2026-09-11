package com.example.ssds.util;
import java.util.Collection;

import java.util.List;
import java.util.stream.Collectors;



import org.springframework.security.core.GrantedAuthority;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.security.core.userdetails.UserDetails;

import com.example.ssds.infra.entity.AppUser;
import com.fasterxml.jackson.annotation.JsonIgnore;



import lombok.AllArgsConstructor;

import lombok.Data;



@Data

@AllArgsConstructor

public class UserDetailsImpl implements UserDetails {

	private Long id;

	private String email;

	private String name;

	@JsonIgnore

	private String password; // 序列化 JSON 時忽略

	private Collection<? extends GrantedAuthority> authorities;



	// 把資料庫 User 轉成 Security 認得的物件

	public static UserDetailsImpl build(AppUser user) {

		List<GrantedAuthority> authorities = user.getRoles().stream()
				.map(role -> new SimpleGrantedAuthority(role.toAuthority()))
				.collect(Collectors.toList());

		return new UserDetailsImpl(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash(), authorities);

	}

	//



	@Override

	public String getUsername() {

		return email;

	} // 用 Email 當帳號



	@Override

	public boolean isAccountNonExpired() {

		return true;

	}



	@Override

	public boolean isAccountNonLocked() {

		return true;

	}



	@Override

	public boolean isCredentialsNonExpired() {

		return true;

	}



	@Override

	public boolean isEnabled() {

		return true;

	}

}


