import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
  function setup(loginResult: 'ok' | 'mfa' | 'fail', totpOk = true) {
    const login = vi.fn().mockReturnValue(of(loginResult));
    const loginTotp = vi.fn().mockReturnValue(of(totpOk));
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { login, loginTotp } },
      ],
    });
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    return { fixture, router, login, loginTotp };
  }

  it('renders a sign in form', () => {
    const { fixture } = setup('fail');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('form')).not.toBeNull();
    expect(el.textContent).toContain('Sign in');
  });

  it('rejects wrong credentials', () => {
    const { fixture } = setup('fail');
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'nope';
    comp.onSubmit();
    expect(comp.error).toBe(true);
  });

  it('logs in with correct credentials and navigates to /', () => {
    const { fixture, router, login } = setup('ok');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'dupa1234';
    comp.onSubmit();
    expect(login).toHaveBeenCalledWith('Adam', 'dupa1234');
    expect(navigate).toHaveBeenCalledWith(['/']);
  });

  it('switches to the TOTP step when the account needs 2FA', () => {
    const { fixture, router, loginTotp } = setup('mfa');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'pw';
    comp.onSubmit();
    expect(comp.step).toBe('totp');
    expect(navigate).not.toHaveBeenCalled();

    comp.code = '123456';
    comp.onTotp();
    expect(loginTotp).toHaveBeenCalledWith('123456');
    expect(navigate).toHaveBeenCalledWith(['/']);
  });
});
