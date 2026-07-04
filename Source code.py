from selenium.webdriver.common.by import By
from bs4 import BeautifulSoup
import time
from selenium.webdriver.common.action_chains import ActionChains
import pandas as pd
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException
import undetected_chromedriver as uc
import re
import os

# Base URL
input_url = input("Please enter URL: ")
base_url = input_url + "/"

options = uc.ChromeOptions()
options.add_argument('--disable-popup-blocking')
options.add_argument('--no-first-run --no-service-autorun --password-store=basic')
driver = uc.Chrome(options=options)
driver.maximize_window()
time.sleep(3)

def clean_instagram_url(url):
    match = re.search(r'next=(https%3A%2F%2Fwww.instagram.com%2F[^&]+)', url)
    if match:
        decoded_url = re.sub(r'%2F', '/', match.group(1))
        decoded_url = re.sub(r'%3A', ':', decoded_url)
        return decoded_url
    return url

def extract_onlyfans_social_links(driver, onlyfans_url, name):
    """Extract social media links from OnlyFans profile"""
    social_links = {'Instagram': [], 'TikTok': [], 'Twitter': [], 'Facebook': []}
    try:
        driver.execute_script("window.open(arguments[0], '_blank');", onlyfans_url)
        time.sleep(20)
        driver.switch_to.window(driver.window_handles[-1])

        # Wait for profile to load
        WebDriverWait(driver, 120).until(
            EC.presence_of_element_located((By.XPATH, "//div[@class='g-user-username']"))
        )
        time.sleep(3)

        # Extract social links
        page_source = driver.page_source
        soup = BeautifulSoup(page_source, 'html.parser')

        # Find the div that contains the social media links
        profile_header = soup.find('div', class_='b-profile__header__user')

        # Iterate through anchor tags inside this div
        if profile_header:
            links = profile_header.find_all('a', class_='b-tabs__nav__link m-tabs-media')

            for link in links:
                href = link.get('href')
                if href and href.startswith("https://onlyfans.com/api2/v2/users/social/buttons/click"):
                    # Find the corresponding span tag to get the platform name
                    span_tag = link.find_next('span', class_='b-tabs__nav__text')
                    if span_tag:
                        platform = span_tag.text.strip().lower()

                        # Check the platform and add the href to the corresponding list
                        if 'tiktok' in platform:
                            social_links['TikTok'].append(href)
                        elif 'instagram' in platform:
                            social_links['Instagram'].append(href)
                        elif 'twitter' in platform or 'x' in platform:
                            social_links['Twitter'].append(href)
                        elif 'facebook' in platform:
                            social_links['Facebook'].append(href)

    except Exception as e:
        print(f"Error extracting OnlyFans social links for {name}: {str(e)}")
    
    finally:
        if len(driver.window_handles) > 1:
            driver.close()
            driver.switch_to.window(driver.window_handles[0])
            time.sleep(3)
    
    return social_links

def extract_profiles(page_source, driver):
    soup = BeautifulSoup(page_source, 'html.parser')
    profiles = []
    profile_elements = soup.find_all('div', class_='result')

    for index, profile_element in enumerate(profile_elements):
        name_tag = profile_element.find('h3')
        name = name_tag.text.strip() if name_tag else ''
        data_arealabel = profile_element.find('a', {'aria-label': True})['aria-label'] if profile_element.find('a', {'aria-label': True}) else ''
        onlyfans_url = f"https://onlyfans.com/{data_arealabel}" if data_arealabel else ''

        if not onlyfans_url:
            continue

        likes = profile_element.find('svg', {'title': 'Likes'}).find_next('strong').text.strip() if profile_element.find('svg', {'title': 'Likes'}) else ''
        social_links = {'Instagram': [], 'TikTok': [], 'Twitter': [], 'Facebook': [], 'Fansly': []}

        # Extract social links from main page
        try:
            social_element = WebDriverWait(driver, 30).until(
                EC.presence_of_element_located(
                    (By.XPATH, f"(//div[@class='result'])[{index + 1}]//div[@class='float-right profile-social']"))
            )
            social_icons = social_element.find_elements(By.TAG_NAME, 'a')

            for icon in social_icons:
                href = icon.get_attribute('href')
                if not href:
                    continue

                try:
                    action = ActionChains(driver)
                    action.context_click(icon).perform()
                    time.sleep(2)

                    real_url = icon.get_attribute("href")
                    if 'instagram.com' in real_url:
                        real_url = clean_instagram_url(real_url)
                        if real_url not in social_links['Instagram']:
                            social_links['Instagram'].append(real_url)
                    elif 'tiktok.com' in real_url:
                        if real_url not in social_links['TikTok']:
                            social_links['TikTok'].append(real_url)
                    elif 'twitter.com' in real_url or 'x.com' in real_url:
                        if real_url not in social_links['Twitter']:
                            social_links['Twitter'].append(real_url)
                    elif 'facebook.com' in real_url:
                        if real_url not in social_links['Facebook']:
                            social_links['Facebook'].append(real_url)
                    elif 'fansly.com' in real_url:
                        if real_url not in social_links['Fansly']:
                            social_links['Fansly'].append(real_url)

                except Exception as e:
                    print(f"Error clicking link {href} for profile {name}: {str(e)}")

        except TimeoutException:
            print(f"Social links not found on main page for {name}")

        # Extract OnlyFans social links from profile
        if onlyfans_url:
            of_social_links = extract_onlyfans_social_links(driver, onlyfans_url, name)
            for platform, links in of_social_links.items():
                social_links[platform].extend(links)

        # Create profile data with all collected links
        profile_data = {
            'Name': name,
            'OnlyFans URL': onlyfans_url,
            'Likes': likes
        }

        # Add all social media links to profile data
        for platform in ['Instagram', 'TikTok', 'Twitter', 'Facebook', 'Fansly']:
            for i in range(max(3, len(social_links[platform]))):  # Ensure at least 3 columns
                key = f'{platform}{i+1}'
                profile_data[key] = social_links[platform][i] if i < len(social_links[platform]) else ''

        save_profiles_to_csv([profile_data])
        profiles.append(profile_data)
        print(f"Profile Data saved for: {name}")

    return profiles

def save_profiles_to_csv(profiles, file_name="profiles.csv"):
    df = pd.DataFrame(profiles)
    if os.path.exists(file_name):
        df.to_csv(file_name, mode='a', header=False, index=False)
    else:
        df.to_csv(file_name, mode='w', header=True, index=False)

driver.get(base_url)
time.sleep(30)

# Set to store unique profile names
unique_profiles = set()

# Start with the first page
page_number = 0

while True:
    new_profiles = []

    for attempt in range(10):  # Try up to 10 times
        url = f"{base_url}{page_number}" if page_number > 0 else base_url
        driver.get(url)
        time.sleep(20)

        try:
            # Wait for the profile elements to be present
            WebDriverWait(driver, 60).until(
                EC.presence_of_all_elements_located((By.CLASS_NAME, 'result'))
            )
        except TimeoutException:
            print(f"Attempt {attempt + 1}: Page took too long to load or no profiles found.")
            if attempt == 9:
                print("Moving to the next page after multiple attempts.")
                break  # Exit the retry loop and move to the next page
            continue  # Retry if not the last attempt

        page_profiles = extract_profiles(driver.page_source, driver)

        if page_profiles:
            new_profiles = [profile for profile in page_profiles if profile['Name'] not in unique_profiles]

            if new_profiles:
                print(f"New profiles found on page {page_number // 24}")
                unique_profiles.update([profile['Name'] for profile in new_profiles])  # Add to the set
            else:
                print(f"Attempt {attempt + 1}: No new profiles found. Retrying...")
            break
        else:
            print(f"Attempt {attempt + 1}: No valid profiles found. Retrying...")

    if not new_profiles:
        print("No more valid profiles found or no new profiles detected. Stopping.")
        break

    page_number += 24

# Close the driver
driver.quit()
