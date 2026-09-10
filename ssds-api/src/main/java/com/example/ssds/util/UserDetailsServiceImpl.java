package com.example.ssds.util;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.security.core.userdetails.UserDetailsService;

import org.springframework.security.core.userdetails.UsernameNotFoundException;

import org.springframework.stereotype.Service;

import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.repository.AppUserRepository;

import jakarta.transaction.Transactional;



@Service

public class UserDetailsServiceImpl implements UserDetailsService {



	@Autowired

	AppUserRepository appUserRepository;



	// Spring Security 唯一任務：根據帳號 (Email) 找到使用者

	@Override

	@Transactional

	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

		AppUser user = appUserRepository.findByEmail(email)

				.orElseThrow(() -> new UsernameNotFoundException("找不到該帳號: " + email));

		return UserDetailsImpl.build(user);

	}

}



