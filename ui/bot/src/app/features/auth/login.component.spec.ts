import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from './auth.service';

describe('LoginComponent', () => {
  function setup(loginResult: boolean) {
    const login = vi.fn().mockReturnValue(of(loginResult));
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { login } },
      ],
    });
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    return { fixture, router, login };
  }

  it('renders a sign in form', () => {
    const { fixture } = setup(false);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('form')).not.toBeNull();
    expect(el.textContent).toContain('Sign in');
  });

  it('rejects wrong credentials', () => {
    const { fixture } = setup(false);
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'nope';
    comp.onSubmit();
    expect(comp.error).toBe(true);
  });

  it('logs in with correct credentials and navigates to /', () => {
    const { fixture, router, login } = setup(true);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const comp = fixture.componentInstance;
    comp.username = 'Adam';
    comp.password = 'dupa1234';
    comp.onSubmit();
    expect(login).toHaveBeenCalledWith('Adam', 'dupa1234');
    expect(navigate).toHaveBeenCalledWith(['/']);
  });
});
